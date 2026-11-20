"""MindGap 云端找茬题服务（FastAPI）。

启动：
    cd server
    pip install -r requirements.txt
    set LLM_API_KEY=...            # 可选；不配置则使用服务端模板
    uvicorn app:app --host 0.0.0.0 --port 8000

接口：
    GET  /health
    POST /v1/quiz/generate
    POST /v1/quiz/answer
"""

from __future__ import annotations

import os

from fastapi import Body, FastAPI, HTTPException
from pydantic import BaseModel

import quiz_logic

app = FastAPI(title="MindGap Cloud Quiz", version="0.1.0")


class AnswerRequest(BaseModel):
    quizId: str
    optionId: str


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "llmConfigured": bool(os.getenv("LLM_API_KEY")),
    }


@app.post("/v1/quiz/generate")
def generate(payload: dict = Body(...)) -> dict:
    try:
        return quiz_logic.generate_quiz(payload)
    except quiz_logic.QuizValidationError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.post("/v1/quiz/answer")
def answer(request: AnswerRequest) -> dict:
    result = quiz_logic.answer_quiz(request.quizId, request.optionId)
    if result is None:
        raise HTTPException(status_code=404, detail="quiz not found")
    return result


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
