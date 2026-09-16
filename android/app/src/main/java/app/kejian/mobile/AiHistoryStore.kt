package app.kejian.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Device-private history. Deliberately not part of AppData, backups, diagnostics or AI context. */
data class AiTaskRecord(
    val id:String, val kind:String, val title:String, val status:String="running",
    val createdAt:Long=System.currentTimeMillis(), val updatedAt:Long=createdAt,
    val jobId:String?=null, val details:String="", val snapshot:String=""
)

class AiHistoryStore(context:Context):SQLiteOpenHelper(context,"kejian_ai_history.db",null,1){
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE messages(owner TEXT NOT NULL,id TEXT NOT NULL,from_user INTEGER NOT NULL,text TEXT NOT NULL,pending INTEGER NOT NULL,error INTEGER NOT NULL,created_at INTEGER NOT NULL,task_id TEXT,PRIMARY KEY(owner,id))")
        db.execSQL("CREATE INDEX messages_time ON messages(owner,created_at)")
        db.execSQL("CREATE TABLE tasks(owner TEXT NOT NULL,id TEXT NOT NULL,kind TEXT NOT NULL,title TEXT NOT NULL,status TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,job_id TEXT,details TEXT NOT NULL,snapshot TEXT NOT NULL,PRIMARY KEY(owner,id))")
        db.execSQL("CREATE INDEX tasks_time ON tasks(owner,created_at)")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
    @Synchronized fun messages(owner:String):List<AiChatMessage> = readableDatabase.rawQuery("SELECT id,from_user,text,pending,error,created_at,task_id FROM messages WHERE owner=? ORDER BY created_at,rowid",arrayOf(owner)).use {cursor->
        buildList {while(cursor.moveToNext())add(AiChatMessage(cursor.getInt(1)!=0,cursor.getString(2),cursor.getInt(3)!=0,cursor.getInt(4)!=0,cursor.getString(0),cursor.getLong(5),cursor.getString(6)))}
    }
    @Synchronized fun tasks(owner:String):List<AiTaskRecord> = readableDatabase.rawQuery("SELECT id,kind,title,status,created_at,updated_at,job_id,details,snapshot FROM tasks WHERE owner=? ORDER BY created_at DESC,rowid DESC",arrayOf(owner)).use {cursor->
        buildList {while(cursor.moveToNext())add(AiTaskRecord(cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),cursor.getLong(4),cursor.getLong(5),cursor.getString(6),cursor.getString(7),cursor.getString(8)))}
    }
    @Synchronized fun saveMessages(owner:String,messages:List<AiChatMessage>){
        val db=writableDatabase;db.beginTransaction()
        try{
            // Removed placeholders never resurrect after a completed/stopped request.
            db.delete("messages","owner=? AND pending=1",arrayOf(owner))
            messages.forEach {message->check(db.insertWithOnConflict("messages",null,ContentValues().apply {
                put("owner",owner);put("id",message.id);put("from_user",if(message.fromUser)1 else 0);put("text",message.text)
                put("pending",if(message.pending)1 else 0);put("error",if(message.error)1 else 0);put("created_at",message.createdAt);put("task_id",message.taskId)
            },SQLiteDatabase.CONFLICT_REPLACE)!=-1L){"Could not save local conversation"}}
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
    @Synchronized fun saveTask(owner:String,task:AiTaskRecord){
        check(writableDatabase.insertWithOnConflict("tasks",null,ContentValues().apply {
            put("owner",owner);put("id",task.id);put("kind",task.kind);put("title",task.title);put("status",task.status)
            put("created_at",task.createdAt);put("updated_at",task.updatedAt);put("job_id",task.jobId);put("details",task.details);put("snapshot",task.snapshot)
        },SQLiteDatabase.CONFLICT_REPLACE)!=-1L){"Could not save local task history"}
    }
    @Synchronized fun ownsTask(owner:String,id:String):Boolean=readableDatabase.rawQuery("SELECT 1 FROM tasks WHERE owner=? AND id=? LIMIT 1",arrayOf(owner,id)).use {it.moveToFirst()}
    @Synchronized fun clear(owner:String){val db=writableDatabase;db.beginTransaction();try {db.delete("messages","owner=?",arrayOf(owner));db.delete("tasks","owner=?",arrayOf(owner));db.setTransactionSuccessful()}finally{db.endTransaction()}}
}
