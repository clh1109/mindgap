"""生成 M4/M5 离线评测语料。

每个会话都是受控的合成时间轴：段落切换、停留、回看、切窗、暂停都可精确控制，
并附带 ground truth 标签。生成结果写入 android/app/src/test/resources/eval/，
由 Kotlin 评测入口直接调用真实的 StuckDetector 与 QuizGenerator。

复现：
    python eval/generate_fixtures.py
"""

from __future__ import annotations

import json
import os
import uuid
from datetime import datetime, timedelta, timezone

BUCKET_MS = 500
BASE = datetime(2026, 11, 18, 9, 0, 0, tzinfo=timezone(timedelta(hours=8)))
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(
    ROOT, "android", "app", "src", "test", "resources", "eval", "sessions"
)
LABELS_PATH = os.path.join(
    ROOT, "android", "app", "src", "test", "resources", "eval", "labels.json"
)

PARAGRAPHS = {
    "p1": "把时间划分为任意长度的任期，任期用连续的整数编号",
    "p2": "领导者通过 AppendEntries 复制日志，多数节点写入后更新 commit index",
    "p3": "任期用连续的整数编号，每个任期最多有一个领导者",
    "p4": "领导者定期生成 Snapshot 并包含最后提交的索引",
}
EXPLAINED_P2 = (
    "领导者通过 AppendEntries（日志复制请求）复制日志，多数节点写入后更新提交位置"
)


def _iso(t_ms: int) -> str:
    return (BASE + timedelta(milliseconds=t_ms)).isoformat()


def build_session(
    name: str,
    segments: list[tuple[str | None, int]],
    actions: dict[int, dict] | None = None,
    speeches: dict[int, tuple[str, int]] | None = None,
    overrides: dict[str, str] | None = None,
) -> dict:
    """segments: [(paragraphId | None(暂停), duration_ms)]，按顺序铺满时间轴。"""
    actions = actions or {}
    speeches = speeches or {}

    texts = dict(PARAGRAPHS)
    texts.update(overrides or {})

    paragraphs = []
    cursor = 0
    for pid in sorted(texts):
        text = texts[pid]
        paragraphs.append(
            {
                "paragraphId": pid,
                "index": int(pid[1:]) - 1,
                "charStart": cursor,
                "charEnd": cursor + len(text),
                "preview": text,
            }
        )
        cursor += len(text)

    frames = []
    t = 0
    previous_pid: str | None = None
    dwell = 0
    entries: dict[str, int] = {}
    frame_index = 0

    for pid, duration in segments:
        for _ in range(duration // BUCKET_MS):
            if pid is None:
                screen = None
                dwell = 0
                previous_pid = None
            else:
                same_visit = pid == previous_pid
                if same_visit:
                    dwell += BUCKET_MS
                else:
                    dwell = 0
                    entries[pid] = entries.get(pid, 0) + 1
                revisit = max(0, entries.get(pid, 1) - 1)
                screen = {
                    "docId": "doc-eval",
                    "paragraphId": pid,
                    "textHash": f"h-{pid}",
                    "isDuplicate": same_visit,
                    "source": "accessibility",
                }
                if not same_visit:
                    screen["text"] = texts[pid]
                previous_pid = pid

            action = actions.get(t, {"type": "none"})
            speech = None
            for start_ms, (text, length_ms) in speeches.items():
                if start_ms <= t < start_ms + length_ms and not frames_has_speech(frames):
                    speech = {
                        "segmentId": f"s{start_ms}",
                        "text": text,
                        "isFinal": True,
                        "startMs": start_ms,
                        "endMs": start_ms + length_ms,
                    }
                    break

            frame = {
                "frameId": f"f{frame_index:04d}",
                "t": t,
                "capturedAt": _iso(t),
                "action": action,
                "features": {
                    "dwellMs": dwell,
                    "revisitCount": 0 if pid is None else max(0, entries.get(pid, 1) - 1),
                    "isStalled": False,
                },
            }
            if screen is not None:
                frame["screen"] = screen
            if speech is not None:
                frame["speech"] = speech
            frames.append(frame)

            t += BUCKET_MS
            frame_index += 1

    # 暂停帧也不该出现 speech
    for frame in frames:
        if "screen" not in frame:
            frame.pop("speech", None)

    session_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"mindgap-eval/{name}"))
    return {
        "schemaVersion": "1.0.0",
        "sessionId": session_id,
        "capture": {
            "startedAt": _iso(0),
            "bucketMs": BUCKET_MS,
            "sources": ["accessibility"],
        },
        "document": {
            "docId": "doc-eval",
            "title": f"评测会话 {name}",
            "totalChars": cursor,
            "paragraphs": paragraphs,
        },
        "frames": frames,
        "stuckEvents": [],
        "quizItems": [],
        "notes": f"eval fixture: {name}",
    }


def frames_has_speech(frames: list[dict]) -> bool:
    return bool(frames) and "speech" in frames[-1]


def visit(pid: str, seconds: float) -> tuple[str, int]:
    return pid, int(seconds * 1000)


def write_fixtures() -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    for file in os.listdir(OUT_DIR):
        if file.endswith(".json"):
            os.remove(os.path.join(OUT_DIR, file))

    labels: list[dict] = []

    def add(name: str, segments, expected, actions=None, speeches=None, overrides=None):
        session = build_session(
            name, segments, actions=actions, speeches=speeches, overrides=overrides
        )
        path = os.path.join(OUT_DIR, f"{name}.json")
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(session, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        labels.append({"file": f"{name}.json", "expected": expected})

    # 1–2 正常会话：不应有任何事件
    add("normal_short", [visit("p1", 17), visit("p2", 17), visit("p3", 17), visit("p4", 17)], [])
    add("normal_calibrated", [visit("p1", 12), visit("p3", 24), visit("p4", 24),
                              visit("p2", 24), visit("p1", 24), visit("p3", 24),
                              visit("p4", 24), visit("p2", 24)], [])

    # 3–4 时间异常
    add("time_short", [visit("p1", 40), visit("p2", 17), visit("p3", 17), visit("p4", 17)],
        [{"kind": "time_anomaly", "paragraphId": "p1"}])
    add("time_calibrated", [visit("p3", 12), visit("p4", 24), visit("p3", 24), visit("p1", 40),
                            visit("p2", 24), visit("p4", 24), visit("p1", 24), visit("p2", 24),
                            visit("p4", 24)],
        [{"kind": "time_anomaly", "paragraphId": "p1"}])

    # 5–6 行为异常：切窗后回看
    add("behavior_short", [visit("p2", 17), visit("p3", 8), visit("p2", 10),
                           visit("p4", 17), visit("p1", 17)],
        [{"kind": "behavior_anomaly", "paragraphId": "p2"}],
        actions={17000: {"type": "switch_app", "appPackage": "com.example.search"}})
    add("behavior_calibrated", [visit("p1", 12), visit("p3", 24), visit("p1", 24),
                                visit("p2", 22), visit("p3", 8), visit("p2", 22),
                                visit("p3", 24), visit("p2", 24), visit("p3", 24),
                                visit("p2", 24)],
        [{"kind": "behavior_anomaly", "paragraphId": "p2"}],
        actions={82000: {"type": "switch_app", "appPackage": "com.example.search"}})

    # 7–8 语义异常：快速跳过未解释术语
    add("semantic_short", [visit("p1", 17), visit("p2", 3.5), visit("p3", 17), visit("p4", 17)],
        [{"kind": "semantic_anomaly", "paragraphId": "p2"}])
    add("semantic_calibrated", [visit("p1", 12), visit("p3", 24), visit("p4", 24),
                                visit("p2", 3.5), visit("p1", 24), visit("p3", 24),
                                visit("p4", 24), visit("p1", 24), visit("p3", 24)],
        [{"kind": "semantic_anomaly", "paragraphId": "p2"}])

    # 9–11 诱饵：暂停、校准期内长停留、超长空档后回看
    add("pause_decoy", [visit("p1", 17), (None, 30000), visit("p2", 17), visit("p3", 17),
                        visit("p4", 17)], [])
    add("warmup_decoy", [visit("p1", 45), visit("p2", 17), visit("p3", 17), visit("p4", 17),
                         visit("p1", 17), visit("p2", 17), visit("p3", 17)], [])
    add("long_gap_decoy", [visit("p2", 17), visit("p3", 17), visit("p4", 17), visit("p1", 17),
                           visit("p3", 17), visit("p4", 17), visit("p1", 17), visit("p2", 17)], [])

    # 12–13 术语已解释：语音提到 / 括号定义
    add("explained_speech_decoy", [visit("p1", 17), visit("p2", 3.5), visit("p3", 17),
                                   visit("p4", 17)], [],
        speeches={17000: ("AppendEntries 和 commit index 都是日志复制里的概念", 3000)})
    add("explained_paren_decoy", [visit("p1", 17), visit("p2", 3.5), visit("p3", 17),
                                  visit("p4", 17)], [],
        overrides={"p2": EXPLAINED_P2})

    # 14–15 组合场景
    add("combined_time_semantic", [visit("p1", 40), visit("p2", 3.5), visit("p3", 17),
                                   visit("p4", 17)],
        [{"kind": "time_anomaly", "paragraphId": "p1"},
         {"kind": "semantic_anomaly", "paragraphId": "p2"}])
    add("combined_behavior_semantic", [visit("p2", 17), visit("p3", 8), visit("p2", 10),
                                       visit("p4", 3.5), visit("p1", 17)],
        [{"kind": "behavior_anomaly", "paragraphId": "p2"},
         {"kind": "semantic_anomaly", "paragraphId": "p4"}],
        actions={17000: {"type": "switch_app", "appPackage": "com.example.search"}})

    with open(LABELS_PATH, "w", encoding="utf-8", newline="\n") as handle:
        json.dump({"sessions": labels}, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    print(f"generated {len(labels)} sessions -> {OUT_DIR}")


if __name__ == "__main__":
    write_fixtures()
