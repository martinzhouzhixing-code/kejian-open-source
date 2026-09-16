import json
import unittest

from pydantic import ValidationError

from audio_summary import SUMMARY_PROTOCOL, transcript_from_seed_result, validate_summary


class SeedAsrResultTests(unittest.TestCase):
    def test_utterances_keep_timestamps_and_speakers(self):
        payload = {"result": {"text": "fallback", "utterances": [
            {"start_time": 1250, "speaker": "1", "text": "今天讨论特征值。"},
            {"start_time": 65250, "speaker_id": "2", "text": "作业周三提交。"},
        ]}}
        self.assertEqual(
            transcript_from_seed_result(payload),
            "[00:00:01] 说话人1 今天讨论特征值。\n[00:01:05] 说话人2 作业周三提交。",
        )

    def test_plain_text_fallback(self):
        self.assertEqual(transcript_from_seed_result({"result": {"text": "A  B\tC"}}), "A B C")


class SummaryProtocolTests(unittest.TestCase):
    def valid(self):
        return {
            "protocol": SUMMARY_PROTOCOL,
            "language": "zh-CN",
            "title": "线性代数第三讲",
            "summary": "矩阵 A 的特征值定义。",
            "keyPoints": ["Ax=λx", "Ax=λx", "  零向量不是特征向量  "],
            "actionItems": ["周三 20:00 前提交第三章第 2、5、7 题"],
            "uncertainties": [],
        }

    def test_accepts_fenced_json_and_deduplicates(self):
        result = validate_summary("```json\n" + json.dumps(self.valid(), ensure_ascii=False) + "\n```")
        self.assertEqual(result.keyPoints, ["Ax=λx", "零向量不是特征向量"])

    def test_rejects_wrong_protocol(self):
        value = self.valid(); value["protocol"] = "KJN1-SUMMARY/1"
        with self.assertRaises(ValidationError):
            validate_summary(value)

    def test_rejects_extra_fields(self):
        value = self.valid(); value["answer"] = "聊天内容"
        with self.assertRaises(ValidationError):
            validate_summary(value)


if __name__ == "__main__":
    unittest.main()
