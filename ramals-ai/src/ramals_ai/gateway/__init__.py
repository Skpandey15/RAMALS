"""The governed model gateway (M1-T05, M1-ADR-008).

Import ``LLMGateway`` from here. Nothing outside ``gateway.providers`` may import a provider SDK;
``tests/unit/test_provider_isolation.py`` enforces that.
"""

from ramals_ai.gateway.errors import GatewayError, GatewayErrorCode
from ramals_ai.gateway.gateway import GatewayResult, LLMGateway

__all__ = ["GatewayError", "GatewayErrorCode", "GatewayResult", "LLMGateway"]
