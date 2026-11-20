"""服务端反事实找茬逻辑测试：不依赖 FastAPI，直接 python test_quiz_logic.py。"""

import json

import quiz_logic

SESSION_ID = "7a3f2c18-55d4-4d21-9c6e-0b6f4a1d9e32"

TIME_REQUEST = {
    "schemaVersion": "1.0.0",
    "sessionId": SESSION_ID,
    "event": {
        "eventId": "e001",
        "kind": "time_anomaly",
        "paragraphId": "p000",
        "dwellMs": 45000,
        "fromMs": 0,
        "toMs": 45000,
    },
    "focus": {
        "paragraphId": "p000",
        "text": "复制状态机 每个服务器保存一份日志，按相同顺序执行相同命令。",
        "evidence": [
            {
                "frameId": "f0000",
                "text": "复制状态机 每个服务器保存一份日志，按相同顺序执行相同命令。",
            }
        ],
    },
    "context": [
        {
            "paragraphId": "p001",
            "text": "任期用连续的整数编号，每个任期最多有一个领导者。",
            "evidence": [
                {
                    "frameId": "f0020",
                    "text": "任期用连续的整数编号，每个任期最多有一个领导者。",
                }
            ],
        },
        {
            "paragraphId": "p002",
            "text": "领导者通过 AppendEntries 复制日志，多数节点写入后更新 commit index。",
            "evidence": [
                {
                    "frameId": "f0040",
                    "text": "领导者通过 AppendEntries 复制日志，多数节点写入后更新 commit index。",
                }
            ],
        },
    ],
    "speech": "",
    "optionCount": 3,
    "questionType": "counterfactual_quote",
    "mutationCount": 2,
    "locale": "zh-CN",
}

SEMANTIC_REQUEST = {
    **TIME_REQUEST,
    "event": {
        "eventId": "e002",
        "kind": "semantic_anomaly",
        "paragraphId": "p002",
        "term": "AppendEntries",
        "dwellMs": 6000,
        "fromMs": 50000,
        "toMs": 56000,
    },
    "focus": {
        "paragraphId": "p002",
        "text": "领导者通过 AppendEntries 复制日志，多数节点写入后更新 commit index。",
        "evidence": [
            {
                "frameId": "f0040",
                "text": "领导者通过 AppendEntries 复制日志，多数节点写入后更新 commit index。",
            }
        ],
    },
    "context": [
        {
            "paragraphId": "p000",
            "text": "复制状态机 每个服务器保存一份日志，按相同顺序执行相同命令。",
            "evidence": [
                {
                    "frameId": "f0000",
                    "text": "复制状态机 每个服务器保存一份日志，按相同顺序执行相同命令。",
                }
            ],
        }
    ],
}


def expect_valid(request: dict) -> dict:
    quiz = quiz_logic.generate_quiz(request)
    problems = quiz_logic.validate_quiz(quiz, request)
    assert not problems, problems
    assert quiz["questionType"] == "counterfactual_quote", quiz
    assert len(quiz["options"]) == 3
    assert [o["optionId"] for o in quiz["options"]] == ["A", "B", "C"]
    assert sum(1 for o in quiz["options"] if o["verdict"] == "correct") == 1

    correct = next(o for o in quiz["options"] if o["verdict"] == "correct")
    assert correct.get("mutation") is None, correct
    assert "f0000" in correct["evidenceFrameIds"] or "f0040" in correct["evidenceFrameIds"]

    for option in quiz["options"]:
        if option["verdict"] == "correct":
            continue
        mutation = option.get("mutation")
        assert isinstance(mutation, dict), option
        assert mutation["editedText"] == option["text"], (mutation, option)
        assert mutation["originalText"] != mutation["editedText"], mutation
        assert mutation["sourceFrameId"] in option["evidenceFrameIds"], mutation
        assert quiz_logic.verify_mutation(mutation), mutation
        # 干扰项必须来自 focus，而不是别的段落
        assert quiz_logic.normalize(mutation["originalText"]) in quiz_logic.normalize(
            request["focus"]["text"]
        ), mutation
    return quiz


def main() -> None:
    time_quiz = expect_valid(TIME_REQUEST)
    correct = next(o for o in time_quiz["options"] if o["verdict"] == "correct")
    assert quiz_logic.normalize(correct["text"]) in quiz_logic.normalize(
        TIME_REQUEST["focus"]["text"]
    ), correct

    answer = quiz_logic.answer_quiz(time_quiz["quizId"], correct["optionId"])
    assert answer and answer["correct"] is True
    wrong_option = next(o for o in time_quiz["options"] if o["verdict"] == "distractor")
    wrong = quiz_logic.answer_quiz(time_quiz["quizId"], wrong_option["optionId"])
    assert wrong and wrong["correct"] is False

    semantic_quiz = expect_valid(SEMANTIC_REQUEST)
    semantic_correct = next(o for o in semantic_quiz["options"] if o["verdict"] == "correct")
    assert "AppendEntries" in semantic_correct["text"], semantic_correct
    assert any(
        "多数→少数" == option["mutation"]["changedToken"]
        for option in semantic_quiz["options"]
        if option["verdict"] == "distractor"
    ), semantic_quiz

    tampered_evidence = json.loads(json.dumps(time_quiz))
    tampered_evidence["options"][0]["evidenceFrameIds"] = ["f0020"]
    assert quiz_logic.validate_quiz(tampered_evidence, TIME_REQUEST), "篡改证据应被拒绝"

    tampered_mutation = json.loads(json.dumps(time_quiz))
    distractor = next(
        o for o in tampered_mutation["options"] if o["verdict"] == "distractor"
    )
    distractor["text"] = "这是一句没有经过规则改写的话，长度也足够通过基本校验。"
    assert quiz_logic.validate_quiz(tampered_mutation, TIME_REQUEST), "篡改改写应被拒绝"

    wrong_type = json.loads(json.dumps(time_quiz))
    wrong_type["questionType"] = "original_choice"
    assert quiz_logic.validate_quiz(wrong_type, TIME_REQUEST), "错误题型应被拒绝"

    # 反事实题不再依赖 context；缺 context 也应能出题
    no_context = expect_valid({**TIME_REQUEST, "context": []})
    assert len(no_context["options"]) == 3

    insufficient_focus = {
        **TIME_REQUEST,
        "focus": {
            "paragraphId": "p000",
            "text": "太短。",
            "evidence": [{"frameId": "f0000", "text": "太短。"}],
        },
    }
    try:
        quiz_logic.generate_quiz(insufficient_focus)
        raise AssertionError("focus 不足以改写时应抛错")
    except quiz_logic.QuizValidationError:
        pass

    # 双重否定：已否定的词不再做否定类改写，伪造的双重否定记录应被拒绝
    negated = quiz_logic.mutate_sentence("该配置不允许客户端复制日志")
    assert all(m["changedToken"] != "允许→不允许" for m in negated), negated
    assert all("不不" not in m["editedText"] for m in negated), negated

    modal = quiz_logic.mutate_sentence("该机制无法保证数据一定写入")
    assert all(m["changedToken"] != "保证→无法保证" for m in modal), modal
    assert all("无法无法" not in m["editedText"] for m in modal), modal

    skipped = quiz_logic.mutate_sentence("不允许直接写入，但允许后台写入")
    negation = next(m for m in skipped if m["changedToken"] == "允许→不允许")
    assert negation["editedText"] == "不允许直接写入，但不允许后台写入", negation
    assert quiz_logic.verify_mutation(negation), negation

    forged = {
        "type": "negation",
        "originalText": "该配置不允许客户端复制日志",
        "editedText": "该配置不不允许客户端复制日志",
        "changedToken": "允许→不允许",
    }
    assert not quiz_logic.verify_mutation(forged), "双重否定伪造应被拒绝"

    print("server counterfactual quiz tests passed")


if __name__ == "__main__":
    main()
