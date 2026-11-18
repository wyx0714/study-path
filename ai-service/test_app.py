import json
import unittest

from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage
from langchain_core.runnables import RunnableLambda

from app import build_chain, create_app


class ApplicationTest(unittest.TestCase):
    def setUp(self):
        self.messages = []

        def fake_model(prompt):
            self.messages.extend(prompt.to_messages())
            return AIMessage(content='{"explanation":"示例","citationIds":["program:ielts"]}')

        self.client = TestClient(create_app(build_chain(RunnableLambda(fake_model)), "local-test-token"))
        self.headers = {"Authorization": "Bearer local-test-token"}
        self.request = {"instructions": '输出 JSON，例如 {"explanation":"..."}', "context": "官网片段与问题"}

    def test_chain_uses_prompt_and_returns_parsed_model_object(self):
        result = self.client.post("/generate", json=self.request, headers=self.headers)
        self.assertEqual(result.status_code, 200)
        self.assertEqual(json.loads(result.json()["content"])["citationIds"], ["program:ielts"])
        self.assertEqual([message.content for message in self.messages], list(self.request.values()))

    def test_unauthorized_request_does_not_invoke_model(self):
        self.assertEqual(self.client.post("/generate", json=self.request).status_code, 401)
        self.assertEqual(self.messages, [])

    def test_malformed_model_output_returns_safe_failure(self):
        broken = build_chain(RunnableLambda(lambda _: AIMessage(content="invalid JSON")))
        client = TestClient(create_app(broken, "local-test-token"))
        self.assertEqual(client.post("/generate", json=self.request, headers=self.headers).status_code, 502)


if __name__ == "__main__":
    unittest.main()
