"""The guard of conftest.py itself (D247): it has to stop a real connection and a real lookup."""

import socket

import pytest


def test_a_connection_outside_loopback_is_stopped() -> None:
    with socket.socket() as sock:
        # Without the guard this fails fast on the timeout instead of hanging the suite.
        sock.settimeout(1)
        with pytest.raises(RuntimeError, match="tried to connect"):
            sock.connect(("203.0.113.1", 443))


def test_a_name_lookup_is_stopped() -> None:
    with pytest.raises(RuntimeError, match="tried to resolve"):
        socket.getaddrinfo("api.openai.com", 443)


def test_loopback_is_allowed() -> None:
    assert socket.getaddrinfo("127.0.0.1", 80)
