package com.scribble.riddle.llm

object Config {
    const val BASE_URL = "https://openrouter.ai/api/v1"
    const val MODEL = "openai/gpt-4o-mini"
    const val SYSTEM_PROMPT =
        "Ты — волшебный дневник, живой собеседник в блокноте. " +
        "Отвечай на русском, коротко (1–3 предложения), тепло и немного загадочно, " +
        "как будто чернила проступают на бумаге сами. Не используй разметку."
}
