"""MindGap 云端反事实找茬题生成逻辑。

不依赖 FastAPI，可单独运行测试。优先调用 OpenAI 兼容接口；未配置或校验失败时
回退到服务端模板，保证接口在断网/无 Key 时仍可演示。

题目结构：1 句原文 + 2 句反事实改写。改写必须由本地词表规则产生，
校验器会重新执行规则比对，避免 LLM 自由发挥出「看似错误但其实为真」的选项。

客户端只上传压缩后的文本片段（focus/context/evidence），不上传音频、截图或原始事件。
"""

from __future__ import annotations

import json
import os
import random
import re
import urllib.request
import zlib
from datetime import datetime, timezone
from typing import Any

MIN_SENTENCE = 12
MAX_SENTENCE = 80
MAX_KEY_STORE = 500
REVERSE_SUFFIX = "（主客互换）"
SENTENCE_BREAKERS = "，。；！？：,.;!?:\n"

DIFFICULTY_RANK = {"easy": 1, "medium": 2, "hard": 3}
MUTATION_TYPES = {"negation", "modality", "quantifier", "numeric", "relation_reverse"}

# (type, from, to, difficulty, prefer_last) —— 必须与 Android CounterfactualMutator 保持一致
TOKEN_RULES: list[tuple[str, str, str, str, bool]] = [
    ("negation", "必须", "不必", "hard", False),
    ("negation", "可以", "不能", "easy", False),
    ("negation", "能够", "无法", "easy", False),
    ("negation", "允许", "不允许", "easy", False),
    ("negation", "保证", "无法保证", "medium", False),
    ("negation", "一定", "不一定", "medium", False),
    ("negation", "支持", "不支持", "easy", False),
    ("negation", "包含", "不包含", "medium", False),
    ("negation", "属于", "不属于", "medium", False),
    ("negation", "相同", "不同", "medium", False),
    ("negation", "不同", "相同", "medium", False),
    ("negation", "连续", "不连续", "medium", True),
    ("negation", "复制", "不复制", "medium", True),
    ("negation", "保存", "不保存", "medium", True),
    ("negation", "执行", "不执行", "medium", True),
    ("negation", "更新", "不更新", "medium", True),
    ("negation", "提交", "不提交", "medium", True),
    ("negation", "返回", "不返回", "medium", True),
    ("negation", "生效", "不生效", "medium", True),
    ("negation", "划分", "不划分", "medium", True),
    ("negation", "生成", "不生成", "medium", True),
    ("modality", "需要", "无需", "medium", False),
    ("modality", "只能", "也能", "hard", False),
    ("modality", "建议", "禁止", "hard", False),
    ("modality", "通过", "绕过", "medium", False),
    ("quantifier", "所有", "部分", "medium", False),
    ("quantifier", "全部", "少数", "medium", False),
    ("quantifier", "多数", "少数", "hard", False),
    ("quantifier", "少数", "多数", "hard", False),
    ("quantifier", "至少", "至多", "medium", False),
    ("quantifier", "至多", "至少", "medium", False),
    ("quantifier", "超过", "少于", "medium", False),
    ("quantifier", "少于", "超过", "medium", False),
    ("quantifier", "每个", "部分", "medium", False),
    ("quantifier", "最多", "最少", "medium", False),
    ("quantifier", "最少", "最多", "medium", False),
    ("quantifier", "任意", "固定", "medium", False),
    ("quantifier", "固定", "任意", "medium", False),
    ("numeric", "过半", "不足一半", "medium", False),
    ("numeric", "两", "三", "easy", False),
    ("numeric", "三", "两", "easy", False),
    ("relation_reverse", "先于", "晚于", "hard", False),
    ("relation_reverse", "之前", "之后", "medium", False),
    ("relation_reverse", "之后", "之前", "medium", False),
    ("relation_reverse", "写入", "读取", "hard", False),
    ("relation_reverse", "读取", "写入", "hard", False),
    ("relation_reverse", "发送", "接收", "hard", False),
    ("relation_reverse", "接收", "发送", "hard", False),
]
RULE_BY_PAIR = {
    (frm, to): (kind, difficulty)
    for kind, frm, to, difficulty, _ in TOKEN_RULES
}
PREFER_LAST = {(frm, to) for _, frm, to, _, prefer_last in TOKEN_RULES if prefer_last}
REVERSE_VERBS = ["导致", "决定", "依赖"]

# 与 Android CounterfactualMutator 保持一致：
# 改写结果以这些字开头视为否定类改写；命中位置前若已有否定前缀，
# 跳过该命中，避免「不允许」→「不不允许」式双重否定。
NEGATION_STARTS = ("不", "无", "未", "非")
NEGATION_PREFIXES = (
    "无法", "不能", "没有", "不必", "无需", "未能", "不会", "不可",
    "不", "无", "未", "非", "没",
)

_KEY_STORE: dict[str, dict[str, Any]] = {}


class QuizValidationError(Exception):
    pass


def normalize(text: str) -> str:
    return "".join(ch for ch in (text or "") if not ch.isspace()).lower()


def split_sentences(text: str) -> list[str]:
    parts = re.split(r"[。！？；\n]", text or "")
    result: list[str] = []
    for part in parts:
        sentence = part.strip(" \t，,、；;:：…")
        if MIN_SENTENCE <= len(sentence) <= MAX_SENTENCE and sentence not in result:
            result.append(sentence)
    return result


def _creates_negation(to: str) -> bool:
    return to.startswith(NEGATION_STARTS)


def _negated_before(text: str, index: int) -> bool:
    if index <= 0:
        return False
    before = text[:index]
    return any(before.endswith(prefix) for prefix in NEGATION_PREFIXES)


def _mutable_index(sentence: str, frm: str, to: str, prefer_last: bool) -> int:
    """按 prefer_last 偏好顺序找第一个可安全改写的位置；全部命中都被否定前缀保护时返回 -1。"""
    guarded = _creates_negation(to)
    indices: list[int] = []
    index = sentence.find(frm)
    while index >= 0:
        indices.append(index)
        index = sentence.find(frm, index + 1)
    if prefer_last:
        indices.reverse()
    for index in indices:
        if not guarded or not _negated_before(sentence, index):
            return index
    return -1


def mutate_sentence(sentence: str) -> list[dict[str, Any]]:
    """按固定词表把一句原文改写成反事实句。"""
    if len(sentence) < MIN_SENTENCE:
        return []
    results: dict[str, dict[str, Any]] = {}

    for kind, frm, to, difficulty, prefer_last in TOKEN_RULES:
        index = _mutable_index(sentence, frm, to, (frm, to) in PREFER_LAST)
        if index < 0:
            continue
        edited = sentence[:index] + to + sentence[index + len(frm):]
        if edited == sentence:
            continue
        changed = f"{frm}→{to}"
        results.setdefault(
            f"{changed}@{index}",
            {
                "type": kind,
                "originalText": sentence,
                "editedText": edited,
                "changedToken": changed,
                "difficulty": difficulty,
            },
        )

    reversal = _reverse_relation(sentence)
    if reversal:
        results.setdefault("reverse@" + reversal["changedToken"], reversal)
    return list(results.values())


def _reverse_relation(sentence: str) -> dict[str, Any] | None:
    for verb in REVERSE_VERBS:
        index = sentence.find(verb)
        if index <= 0:
            continue
        left = sentence[:index].strip()
        right = sentence[index + len(verb):].strip()
        if not (2 <= len(left) <= 28):
            continue
        if not (2 <= len(right) <= 28):
            continue
        if any(ch in SENTENCE_BREAKERS for ch in left + right):
            continue
        edited = right + verb + left
        if edited == sentence:
            continue
        return {
            "type": "relation_reverse",
            "originalText": sentence,
            "editedText": edited,
            "changedToken": f"{verb}{REVERSE_SUFFIX}",
            "difficulty": "hard",
        }
    return None


def apply_rule(original: str, frm: str, to: str) -> str | None:
    index = original.find(frm)
    if index < 0:
        return None
    return original[:index] + to + original[index + len(frm):]


def verify_mutation(mutation: dict[str, Any]) -> bool:
    """重新执行规则，确认改写确实由本地词表产生。"""
    original = mutation.get("originalText") or ""
    edited = mutation.get("editedText") or ""
    changed = mutation.get("changedToken") or ""
    kind = mutation.get("type")
    if not original or not edited or not changed:
        return False
    if changed.endswith(REVERSE_SUFFIX):
        reversal = _reverse_relation(original)
        return bool(reversal) and kind == "relation_reverse" and reversal["editedText"] == edited
    parts = changed.split("→")
    if len(parts) != 2:
        return False
    frm, to = parts
    rule = RULE_BY_PAIR.get((frm, to))
    if rule is None or rule[0] != kind:
        return False
    guarded = _creates_negation(to)
    index = original.find(frm)
    while index >= 0:
        if not guarded or not _negated_before(original, index):
            candidate = original[:index] + to + original[index + len(frm):]
            if candidate == edited:
                return True
        index = original.find(frm, index + 1)
    return False


def _allowed_evidence(request: dict[str, Any]) -> dict[str, dict[str, Any]]:
    allowed: dict[str, dict[str, Any]] = {}
    focus = request.get("focus") or {}
    for item in focus.get("evidence") or []:
        allowed[item["frameId"]] = {
            "frameId": item["frameId"],
            "paragraphId": focus.get("paragraphId"),
            "text": item.get("text") or "",
        }
    for ctx in request.get("context") or []:
        for item in ctx.get("evidence") or []:
            allowed[item["frameId"]] = {
                "frameId": item["frameId"],
                "paragraphId": ctx.get("paragraphId"),
                "text": item.get("text") or "",
            }
    return allowed


def _focus_text(request: dict[str, Any]) -> str:
    focus = request.get("focus") or {}
    parts = [focus.get("text") or ""]
    parts.extend(item.get("text") or "" for item in focus.get("evidence") or [])
    return normalize("\n".join(parts))


def _all_text(request: dict[str, Any]) -> str:
    parts = [_focus_text(request)]
    for ctx in request.get("context") or []:
        parts.append(normalize(ctx.get("text") or ""))
        parts.extend(normalize(item.get("text") or "") for item in ctx.get("evidence") or [])
    return normalize("\n".join(parts))


def validate_quiz(quiz: dict[str, Any], request: dict[str, Any]) -> list[str]:
    problems: list[str] = []
    options = quiz.get("options") or []
    if len(options) != 3:
        problems.append(f"选项数不是 3：{len(options)}")
    if [o.get("optionId") for o in options] != ["A", "B", "C"]:
        problems.append("选项编号不是 A/B/C")
    if sum(1 for o in options if o.get("verdict") == "correct") != 1:
        problems.append("正确项数量不是 1")
    if len({normalize(o.get("text") or "") for o in options}) != len(options):
        problems.append("选项文本重复")
    if not (quiz.get("stem") or "").strip():
        problems.append("题干为空")
    if not (quiz.get("explanation") or "").strip():
        problems.append("解析为空")
    if quiz.get("questionType") not in (None, "counterfactual_quote"):
        problems.append("题型不是反事实找茬")
    if quiz.get("difficulty") not in (None, "easy", "medium", "hard"):
        problems.append("难度取值不合法")

    allowed = _allowed_evidence(request)
    focus_pid = (request.get("focus") or {}).get("paragraphId")
    focus_norm = _focus_text(request)
    all_norm = _all_text(request)

    for option in options:
        option_id = option.get("optionId")
        evidence_ids = option.get("evidenceFrameIds") or []
        if not evidence_ids:
            problems.append(f"{option_id} 缺少证据帧")
            continue
        missing = [fid for fid in evidence_ids if fid not in allowed]
        if missing:
            problems.append(f"{option_id} 引用了未提供的证据帧：{missing}")
            continue
        needle = normalize(option.get("text") or "")

        if option.get("verdict") == "correct":
            grounded = any(
                allowed[fid].get("paragraphId") == focus_pid
                and needle in normalize(allowed[fid]["text"])
                for fid in evidence_ids
            )
            if not grounded:
                problems.append(f"{option_id} 正确项无法回指卡壳段原文")
            continue

        mutation = option.get("mutation")
        if not isinstance(mutation, dict):
            problems.append(f"{option_id} 缺少反事实改写记录")
            continue
        if mutation.get("type") not in MUTATION_TYPES:
            problems.append(f"{option_id} 改写类型不合法")
        if normalize(mutation.get("editedText") or "") != needle:
            problems.append(f"{option_id} 改写文本与选项不一致")
        if not verify_mutation(mutation):
            problems.append(f"{option_id} 改写不符合本地规则")
        source_frame = mutation.get("sourceFrameId")
        if source_frame not in evidence_ids:
            problems.append(f"{option_id} 来源帧不在证据帧中")
        if source_frame not in allowed:
            problems.append(f"{option_id} 来源帧未提供")
        original = normalize(mutation.get("originalText") or "")
        edited = normalize(mutation.get("editedText") or "")
        if original == edited:
            problems.append(f"{option_id} 改写前后相同")
        if not original or original not in focus_norm:
            problems.append(f"{option_id} 改写原文不在卡壳段")
        if edited and edited in all_norm:
            problems.append(f"{option_id} 改写句在文档中真实存在")
        grounded = any(
            allowed[fid].get("paragraphId") == focus_pid
            and original in normalize(allowed[fid]["text"])
            for fid in evidence_ids
        )
        if not grounded:
            problems.append(f"{option_id} 改写证据不成立")

    return problems


def _change_text(token: str) -> str:
    parts = token.split("→")
    if len(parts) == 2:
        return f"把「{parts[0]}」改成了「{parts[1]}」"
    return "把主客顺序互换"


def _template_quiz(request: dict[str, Any]) -> dict[str, Any]:
    focus = request.get("focus") or {}
    focus_sentences = split_sentences(focus.get("text") or "")
    if not focus_sentences:
        raise QuizValidationError("focus 文本不足以拆句")

    event = request.get("event") or {}
    term = (event.get("term") or "").strip()
    preferred = [s for s in focus_sentences if term.lower() in s.lower()] if term else []
    source_sentences = preferred or focus_sentences

    focus_evidence = focus.get("evidence") or []
    all_norm = _all_text(request)
    candidates: list[dict[str, Any]] = []
    by_sentence: dict[str, list[dict[str, Any]]] = {}

    for sentence in source_sentences:
        evidence_ids = [
            item["frameId"]
            for item in focus_evidence
            if normalize(sentence) in normalize(item.get("text") or "")
        ][:2]
        if not evidence_ids:
            continue
        mutations = [
            mutation
            for mutation in mutate_sentence(sentence)
            if normalize(mutation["editedText"]) not in all_norm
        ]
        if not mutations:
            continue
        by_sentence[sentence] = mutations
        for mutation in mutations:
            candidates.append(
                {
                    "sourceSentence": sentence,
                    "mutation": mutation,
                    "evidenceFrameIds": evidence_ids,
                }
            )

    if not candidates:
        raise QuizValidationError("卡壳段没有可用的反事实改写句")

    def has_hard(sentence: str) -> bool:
        return any(m["difficulty"] == "hard" for m in by_sentence.get(sentence, []))

    correct = max(by_sentence, key=lambda sentence: (has_hard(sentence), len(sentence)))

    pool = [c for c in candidates if c["sourceSentence"] != correct]
    same_source = [c for c in candidates if c["sourceSentence"] == correct]

    def rank(candidate: dict[str, Any]) -> tuple:
        mutation = candidate["mutation"]
        return (
            -DIFFICULTY_RANK[mutation["difficulty"]],
            abs(len(mutation["editedText"]) - len(correct)),
        )

    pool.sort(key=rank)
    same_source.sort(key=rank)
    chosen: list[dict[str, Any]] = []
    used_sentences: set[str] = set()
    for candidate in same_source + pool:
        if len(chosen) >= 2:
            break
        if candidate["sourceSentence"] in used_sentences:
            continue
        chosen.append(candidate)
        used_sentences.add(candidate["sourceSentence"])
    for candidate in same_source + pool:
        if len(chosen) >= 2:
            break
        if candidate in chosen:
            continue
        if normalize(candidate["mutation"]["editedText"]) in {
            normalize(c["mutation"]["editedText"]) for c in chosen
        }:
            continue
        chosen.append(candidate)
    if len(chosen) < 2:
        raise QuizValidationError("卡壳段不足以生成两条反事实改写")

    correct_evidence = [
        item["frameId"]
        for item in focus_evidence
        if normalize(correct) in normalize(item.get("text") or "")
    ][:2]
    entries: list[dict[str, Any]] = [
        {
            "text": correct,
            "verdict": "correct",
            "evidenceFrameIds": correct_evidence,
            "rationale": "正确：该句可在卡壳段的原文中逐字找到。",
        }
    ]
    for candidate in chosen:
        mutation = candidate["mutation"]
        entries.append(
            {
                "text": mutation["editedText"],
                "verdict": "distractor",
                "evidenceFrameIds": candidate["evidenceFrameIds"],
                "rationale": (
                    f"反事实：原文是「{mutation['originalText']}」，"
                    f"这项{_change_text(mutation['changedToken'])}，与原文不符。"
                ),
                "mutation": {
                    "type": mutation["type"],
                    "originalText": mutation["originalText"],
                    "editedText": mutation["editedText"],
                    "changedToken": mutation["changedToken"],
                    "sourceFrameId": candidate["evidenceFrameIds"][0],
                },
            }
        )

    event_id = event.get("eventId") or ""
    random.Random(zlib.crc32(event_id.encode("utf-8"))).shuffle(entries)
    options = []
    for index, entry in enumerate(entries):
        options.append(
            {
                "optionId": chr(ord("A") + index),
                "text": entry["text"],
                "verdict": entry["verdict"],
                "rationale": entry["rationale"],
                "evidenceFrameIds": entry["evidenceFrameIds"],
                **({"mutation": entry["mutation"]} if "mutation" in entry else {}),
            }
        )

    changes = "」「".join(m["mutation"]["changedToken"] for m in chosen)
    hardest = max(chosen, key=lambda c: DIFFICULTY_RANK[c["mutation"]["difficulty"]])
    correct_option = next(o for o in options if o["verdict"] == "correct")
    return {
        "schemaVersion": "1.0.0",
        "quizId": f"q-{event_id}",
        "eventId": event_id,
        "mode": "online",
        "questionType": "counterfactual_quote",
        "difficulty": hardest["mutation"]["difficulty"],
        "stem": _stem(request),
        "options": options,
        "explanation": (
            f"正确项 {correct_option['optionId']} 可在卡壳段原文中逐字找到；"
            f"另外两项分别改写了「{changes}」，意思与原句不同。"
        ),
        "model": "server-counterfactual-v1",
        "createdAt": datetime.now(timezone.utc).isoformat(),
    }


def _stem(request: dict[str, Any]) -> str:
    event = request.get("event") or {}
    kind = event.get("kind")
    suffix = "下面哪一句与原文完全一致？另外两句各被改了一个词。"
    if kind == "semantic_anomaly":
        term = event.get("term") or "该术语"
        return f"术语「{term}」出现在你快速跳过的那一段。{suffix}"
    if kind == "behavior_anomaly":
        if event.get("switchedApp"):
            return f"你切到其他应用后回看了这一段。{suffix}"
        return f"你曾回看这一段。{suffix}"
    dwell = int((event.get("dwellMs") or 0) / 1000)
    return f"你在这一段停留了 {dwell}s。{suffix}"


SYSTEM_PROMPT = """你是 MindGap 的反事实找茬题生成器。
规则：
1. 只能使用用户提供的 focus 原文，禁止使用 context 里的句子作为选项。
2. 生成恰好 3 个选项：1 个正确项从 focus 原文逐字摘一句；
   2 个干扰项必须是 focus 原文整句的「反事实改写」。
3. 每个干扰项必须给出 mutation：
   {"type":"...","originalText":"原文整句","editedText":"改写整句",
    "changedToken":"原词→改词","sourceFrameId":"f0001"}
   editedText 必须与选项 text 完全一致；originalText 必须是 focus 原文中的整句。
4. 允许的改写（必须逐字使用，不能自创）：
   negation：必须→不必、可以→不能、能够→无法、允许→不允许、保证→无法保证、
             一定→不一定、支持→不支持、包含→不包含、属于→不属于、相同→不同、不同→相同、
             连续→不连续、复制→不复制、保存→不保存、执行→不执行、更新→不更新、
             提交→不提交、返回→不返回、生效→不生效、划分→不划分、生成→不生成
   modality：需要→无需、只能→也能、建议→禁止、通过→绕过
   quantifier：所有→部分、全部→少数、多数→少数、少数→多数、至少→至多、至多→至少、
               超过→少于、少于→超过、每个→部分、最多→最少、最少→最多、任意→固定、固定→任意
   numeric：过半→不足一半、两→三、三→两
   relation_reverse：先于→晚于、之前→之后、之后→之前、写入→读取、读取→写入、
                     发送→接收、接收→发送
5. 每个选项给出 evidenceFrameIds，只能引用 focus 里的 frameId；
   干扰项的 sourceFrameId 必须是包含 originalText 的帧。
6. 改写后的句子不能恰好是 focus 或 context 中另一句真话。
7. 原词前已有「不/无/未/非/没/无法/不能」等否定词时，不得再对该处做否定类改写，
   避免「不不允许」式双重否定；可改选同句中其他未否定的位置。
8. 只输出 JSON，不要 markdown 代码块。JSON 结构：
{"quizId":"q-eventId","eventId":"...","mode":"online",
 "questionType":"counterfactual_quote","difficulty":"easy|medium|hard",
 "stem":"...","options":[{"optionId":"A","text":"...","verdict":"correct|distractor",
 "rationale":"...","evidenceFrameIds":["f0001"],
 "mutation":{"type":"...","originalText":"...","editedText":"...",
             "changedToken":"...","sourceFrameId":"f0001"}}],"explanation":"..."}
正确项不要带 mutation。
"""


def _extract_json(content: str) -> dict[str, Any]:
    text = content.strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\n?", "", text)
        text = re.sub(r"\n?```$", "", text)
    return json.loads(text)


def _llm_quiz(request: dict[str, Any]) -> dict[str, Any] | None:
    api_key = os.getenv("LLM_API_KEY")
    if not api_key:
        return None
    base_url = os.getenv("LLM_BASE_URL", "https://api.openai.com/v1").rstrip("/")
    model = os.getenv("LLM_MODEL", "gpt-4o-mini")
    payload = {
        "model": model,
        "temperature": 0.4,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {
                "role": "user",
                "content": json.dumps(request, ensure_ascii=False),
            },
        ],
    }
    try:
        http_request = urllib.request.Request(
            f"{base_url}/chat/completions",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {api_key}",
            },
            method="POST",
        )
        with urllib.request.urlopen(http_request, timeout=30) as response:
            data = json.loads(response.read().decode("utf-8"))
        content = data["choices"][0]["message"]["content"]
        quiz = _extract_json(content)
        for option in quiz.get("options") or []:
            mutation = option.get("mutation")
            if isinstance(mutation, dict):
                option["mutation"] = {
                    key: mutation.get(key)
                    for key in (
                        "type",
                        "originalText",
                        "editedText",
                        "changedToken",
                        "sourceFrameId",
                    )
                }
        event = request.get("event") or {}
        quiz["schemaVersion"] = "1.0.0"
        quiz["quizId"] = f"q-{event.get('eventId')}"
        quiz["eventId"] = event.get("eventId")
        quiz["mode"] = "online"
        quiz["model"] = model
        quiz.setdefault("questionType", "counterfactual_quote")
        quiz.setdefault("difficulty", "medium")
        quiz["createdAt"] = datetime.now(timezone.utc).isoformat()
        return quiz
    except Exception:
        return None


def generate_quiz(request: dict[str, Any]) -> dict[str, Any]:
    """优先 LLM，严格校验；失败回退服务端模板，并登记答案键。"""
    focus = request.get("focus") or {}
    if not focus.get("text") or not focus.get("evidence"):
        raise QuizValidationError("focus 为空或缺少证据")

    llm_quiz = _llm_quiz(request)
    if llm_quiz is not None and not validate_quiz(llm_quiz, request):
        quiz = llm_quiz
    else:
        quiz = _template_quiz(request)
        problems = validate_quiz(quiz, request)
        if problems:
            raise QuizValidationError("模板题未通过校验：" + "；".join(problems))

    correct = next(o for o in quiz["options"] if o["verdict"] == "correct")
    _KEY_STORE[quiz["quizId"]] = {
        "correctOptionId": correct["optionId"],
        "rationale": correct.get("rationale"),
        "evidenceFrameIds": correct.get("evidenceFrameIds") or [],
        "createdAt": quiz.get("createdAt"),
    }
    if len(_KEY_STORE) > MAX_KEY_STORE:
        oldest = next(iter(_KEY_STORE))
        _KEY_STORE.pop(oldest, None)
    return quiz


def answer_quiz(quiz_id: str, option_id: str) -> dict[str, Any] | None:
    entry = _KEY_STORE.get(quiz_id)
    if entry is None:
        return None
    return {
        "quizId": quiz_id,
        "correct": entry["correctOptionId"] == option_id,
        "correctOptionId": entry["correctOptionId"],
        "rationale": entry["rationale"],
        "evidenceFrameIds": entry["evidenceFrameIds"],
    }
