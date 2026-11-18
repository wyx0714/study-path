import hmac
import json
import logging
import os

from fastapi import FastAPI, Header, HTTPException
from langchain_core.output_parsers import JsonOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_deepseek import ChatDeepSeek
from pydantic import BaseModel, Field


log = logging.getLogger(__name__)


class ModelRequest(BaseModel):
    instructions: str = Field(min_length=1, max_length=6000)
    context: str = Field(min_length=1, max_length=18000)


def build_chain(model):
    prompt = ChatPromptTemplate.from_messages([
        ("system", "{instructions}"),
        ("human", "{context}"),
    ])
    return prompt | model | JsonOutputParser()


def create_app(chain=None, token=None):
    token = token if token is not None else os.getenv("STUDY_LANGCHAIN_TOKEN", "")
    key = os.getenv("DEEPSEEK_API_KEY", "")
    if chain is None and key and token:
        model = ChatDeepSeek(
            model="deepseek-flash", api_key=key, timeout=18, max_retries=0, max_tokens=600,
        ).bind(response_format={"type": "json_object"}, extra_body={"thinking": {"type": "disabled"}})
        chain = build_chain(model)

    app = FastAPI(title="StudyPath LangChain", docs_url=None, redoc_url=None, openapi_url=None)

    @app.get("/health")
    def health():
        return {"framework": "LangChain", "provider": "DeepSeek", "configured": bool(chain is not None and token)}

    @app.post("/generate")
    def generate(request: ModelRequest, authorization: str = Header(default="")):
        if not token or chain is None:
            raise HTTPException(status_code=503, detail="Generation is not configured")
        if not hmac.compare_digest(authorization.encode(), ("Bearer " + token).encode()):
            raise HTTPException(status_code=401, detail="Unauthorized")
        try:
            result = chain.invoke(request.model_dump())
            if not isinstance(result, dict) or not result:
                raise ValueError("Expected a JSON object")
            return {"content": json.dumps(result, ensure_ascii=False)}
        except Exception as error:
            log.warning("Model generation failed (%s)", type(error).__name__)
            raise HTTPException(status_code=502, detail="Generation unavailable") from None

    return app


app = create_app()
