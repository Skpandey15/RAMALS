"""MCP-3 (M2-ADR-031): Python client integration for the five Java-hosted MCP-2 read-only
capabilities.

Java remains authoritative throughout. This package never computes mastery, diagnostic confidence,
longitudinal state, or any H6/H7/mastery semantics -- it authenticates, transports a request, and
maps the response into a typed, read-only result. See ``client.py`` for the boundary in full.
"""

from __future__ import annotations
