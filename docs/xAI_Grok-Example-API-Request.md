# API Examples, copied from https://docs.x.ai/overview

## 1
curl https://api.x.ai/v1/responses \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $XAI_API_KEY" \
    -d '{
      "model": "grok-4.20-reasoning",
      "input": "What is the meaning of life, the universe, and everything?"
    }'

## 2
curl https://api.x.ai/v1/responses \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $XAI_API_KEY" \
  -d '{
    "model": "grok-4.3",
    "input": [
        {
            "role": "system",
            "content": "You are Grok, a chatbot inspired by the Hitchhiker'\''s Guide to the Galaxy."
        },
        {
            "role": "user",
            "content": "What is the meaning of life, the universe, and everything?"
        }
    ]
}'


