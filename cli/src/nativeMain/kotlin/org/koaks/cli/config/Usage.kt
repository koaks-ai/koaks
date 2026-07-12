package org.koaks.cli.config

internal fun usageText(): String =
    """
    Usage:
      koaks [options]

    Options:
      -h, --help

    Diagnostics:
      KOAKS_TRACE_FILE=path   Write timing-only CLI lifecycle logs to path.

    Config:
      Default path: ${'$'}HOME/.koaks/config.toml

      provider = "openai"
      show_reasoning = false

      [providers.openai]
      base_url = "https://api.openai.com"
      api_key = "sk-..."
      model = "gpt-5.5"
      model_list = ["gpt-5.5"]

      [providers.anthropic]
      base_url = "https://api.anthropic.com/v1/messages"
      api_key = "sk-ant-..."
      model = "claude-opus-4-8"
      model_list = ["claude-opus-4-8"]

    Examples:
      koaks
      /provider anthropic
      /model claude-opus-4-8
      /reasoning on
    """.trimIndent()
