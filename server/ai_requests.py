"""Owner-scoped cancellation, including cancel-before-arrival and result races."""
import asyncio
import re
from contextlib import suppress

from fastapi import HTTPException


class AiRequestManager:
    def __init__(self, db, clock):
        self.db, self.clock = db, clock
        self.tasks = {}

    def init_schema(self):
        with self.db() as c:
            c.execute('''CREATE TABLE IF NOT EXISTS ai_requests(
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                request_id TEXT NOT NULL, job_id TEXT, state TEXT NOT NULL,
                charged_tokens INTEGER NOT NULL DEFAULT 0, quota_epoch INTEGER,
                updated_at INTEGER NOT NULL, PRIMARY KEY(user_id,request_id))''')
            c.execute('CREATE INDEX IF NOT EXISTS ai_requests_age_idx ON ai_requests(updated_at)')

    @staticmethod
    def validate(request_id):
        if not re.fullmatch(r'[a-zA-Z0-9_-]{16,80}', request_id or ''):
            raise HTTPException(400, '任务编号无效')

    def begin(self, user_id, request_id, job_id):
        if not request_id:
            return
        self.validate(request_id)
        with self.db() as c:
            c.execute('BEGIN IMMEDIATE')
            c.execute('DELETE FROM ai_requests WHERE updated_at<?', (self.clock()-86400,))
            row = c.execute('SELECT state FROM ai_requests WHERE user_id=? AND request_id=?', (user_id,request_id)).fetchone()
            if row:
                raise HTTPException(409, '识别已停止' if row['state']=='cancelled' else '该任务已经提交，请勿重复发送')
            c.execute('INSERT INTO ai_requests(user_id,request_id,job_id,state,updated_at) VALUES(?,?,?,\'running\',?)',
                      (user_id,request_id,job_id,self.clock()))
        self.tasks[(user_id,request_id)] = asyncio.current_task()

    def release(self, user_id, request_id):
        if request_id and self.tasks.get((user_id,request_id)) is asyncio.current_task():
            self.tasks.pop((user_id,request_id), None)

    def is_cancelled(self, user_id, request_id):
        if not request_id:
            return False
        with self.db() as c:
            row=c.execute('SELECT state FROM ai_requests WHERE user_id=? AND request_id=?',(user_id,request_id)).fetchone()
            return row is not None and row['state']=='cancelled'

    def cancel(self, user_id, request_id):
        self.validate(request_id)
        with self.db() as c:
            c.execute('BEGIN IMMEDIATE')
            c.execute('DELETE FROM ai_requests WHERE updated_at<?',(self.clock()-86400,))
            row=c.execute('SELECT * FROM ai_requests WHERE user_id=? AND request_id=?',(user_id,request_id)).fetchone()
            if row and row['job_id']:
                job=c.execute('SELECT status FROM ai_jobs WHERE id=? AND user_id=?',(row['job_id'],user_id)).fetchone()
                if job and job['status'] in ('applied','applying'):
                    return {'cancelled':False,'status':'applied','clientRequestId':request_id}
            if row and row['charged_tokens']:
                # A late cancellation may race the delivered preview. Refund once,
                # and never subtract usage belonging to a later monthly period.
                c.execute('UPDATE users SET ai_token_used=MAX(0,ai_token_used-?) WHERE id=? AND quota_reset_at IS ?',
                          (row['charged_tokens'],user_id,row['quota_epoch']))
            c.execute('''INSERT INTO ai_requests(user_id,request_id,state,updated_at)
                         VALUES(?,?, 'cancelled',?) ON CONFLICT(user_id,request_id)
                         DO UPDATE SET state='cancelled',charged_tokens=0,updated_at=excluded.updated_at''',
                      (user_id,request_id,self.clock()))
            if row and row['job_id']:
                c.execute("UPDATE ai_jobs SET status='cancelled',result_json=NULL,error=NULL,finished_at=? WHERE id=? AND user_id=? AND status NOT IN ('applied','applying')",
                          (self.clock(),row['job_id'],user_id))
        task=self.tasks.get((user_id,request_id))
        if task and task is not asyncio.current_task() and not task.done():
            task.cancel()
        return {'cancelled':True,'status':'cancelled','clientRequestId':request_id}

    def status(self, user_id, request_id):
        """Read a submitted preview without re-running a model or charging usage."""
        import json
        self.validate(request_id)
        with self.db() as c:
            row = c.execute('SELECT * FROM ai_requests WHERE user_id=? AND request_id=?',
                            (user_id, request_id)).fetchone()
            if row is None:
                raise HTTPException(404, '未找到该任务')
            if row['updated_at'] < self.clock() - 86400:
                raise HTTPException(410, '任务恢复期限已过，请重新提交')
            job = c.execute('SELECT * FROM ai_jobs WHERE id=? AND user_id=?',
                            (row['job_id'], user_id)).fetchone() if row['job_id'] else None
            state = 'cancelled' if row['state'] == 'cancelled' else job['status'] if job else row['state']
            response = {'requestId': request_id, 'clientRequestId': request_id,
                        'status': state, 'jobId': row['job_id']}
            # Never return cancelled or already-applied operations as a fresh preview.
            if state == 'preview' and job and job['result_json']:
                result = json.loads(job['result_json'])
                if not isinstance(result, dict):
                    raise HTTPException(500, '任务结果无法恢复')
                response['result'] = {**result, 'jobId': row['job_id'], 'clientRequestId': request_id,
                    'usage': {'inputTokens': job['input_tokens'], 'outputTokens': job['output_tokens'],
                              'imageCount': job['image_count'], 'latencyMs': job['latency_ms']}}
            elif state == 'failed':
                # Provider exceptions can contain request details; do not expose raw error logs.
                response['error'] = '任务未完成，请重新提交'
                detail = (job['error'] or '') if job else ''
                if detail.startswith('INPUT_REQUIRED:'):
                    response.update(errorCode='INPUT_REQUIRED', error=detail[len('INPUT_REQUIRED:'):].strip())
                elif 'AI provider API 404' in detail:
                    response.update(errorCode='MODEL_UNAVAILABLE', error='AI 模型暂时不可用，本次未扣额度。请稍后重新发送。')
            elif state == 'running' and self.clock() - row['updated_at'] > 30 and (user_id, request_id) not in self.tasks:
                response.update(status='failed', error='任务已中断，请重新提交')
            return response

    async def run(self, request, operation, user_id, request_id, *, keep_running_on_disconnect=False):
        # Starlette's is_disconnected() uses a cancelled AnyIO scope. Cancelling
        # the monitor inside that scope can be swallowed, so task.cancel alone
        # must never be our shutdown signal (it can hang a successful request).
        stopped = asyncio.Event()
        async def watch():
            while not stopped.is_set():
                if self.is_cancelled(user_id,request_id) or (not (keep_running_on_disconnect and request_id) and await request.is_disconnected()):
                    return
                try:
                    await asyncio.wait_for(stopped.wait(), timeout=.4)
                except TimeoutError:
                    pass
        work=asyncio.create_task(operation)
        monitor=asyncio.create_task(watch())
        try:
            # New clients explicitly opt in to durable execution; a disconnected
            # socket must not undo completed work. Explicit Stop still cancels it.
            done,_=await asyncio.wait((work,monitor),timeout=600,return_when=asyncio.FIRST_COMPLETED)
            if not done:
                raise TimeoutError('AI task exceeded the execution deadline')
            if monitor in done:
                raise asyncio.CancelledError()
            return await work
        finally:
            stopped.set()
            work.cancel(); monitor.cancel()
            with suppress(asyncio.CancelledError):
                await monitor
            with suppress(asyncio.CancelledError,Exception):
                await work

    def complete(self, user_id, request_id, job_id, result, usage, developer):
        import json
        count=max(0,int(usage.get('inputTokens',0))+int(usage.get('outputTokens',0)))
        charge=0 if developer else count
        with self.db() as c:
            c.execute('BEGIN IMMEDIATE')
            if request_id:
                row=c.execute('SELECT state,job_id FROM ai_requests WHERE user_id=? AND request_id=?',(user_id,request_id)).fetchone()
                if not row or row['state']=='cancelled':
                    raise asyncio.CancelledError()
                if row['job_id'] != job_id or row['state'] != 'running':
                    raise HTTPException(409, '任务编号与结果不匹配，请勿重复完成')
            changed=c.execute("UPDATE ai_jobs SET status='preview',course_count=?,input_tokens=?,output_tokens=?,image_count=?,latency_ms=?,result_json=?,finished_at=? WHERE id=? AND user_id=? AND status='running'",
                      (len(result['operations']),usage.get('inputTokens',0),usage.get('outputTokens',0),usage.get('imageCount',0),usage.get('latencyMs',0),json.dumps(result,ensure_ascii=False),self.clock(),job_id,user_id)).rowcount
            if changed != 1:
                raise HTTPException(409, '任务已结束，请勿重复完成')
            if charge:
                c.execute('UPDATE users SET ai_token_used=ai_token_used+? WHERE id=?',(charge,user_id))
            if request_id:
                user=c.execute('SELECT quota_reset_at FROM users WHERE id=?',(user_id,)).fetchone()
                c.execute("UPDATE ai_requests SET state='preview',charged_tokens=?,quota_epoch=?,updated_at=? WHERE user_id=? AND request_id=?",
                          (charge,user['quota_reset_at'],self.clock(),user_id,request_id))

    def stopped(self, user_id, request_id, job_id):
        if request_id:
            self.cancel(user_id,request_id)
        else:
            with self.db() as c:
                c.execute("UPDATE ai_jobs SET status='cancelled',error=NULL,result_json=NULL,finished_at=? WHERE id=? AND user_id=? AND status='running'",(self.clock(),job_id,user_id))
