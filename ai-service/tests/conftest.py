"""Tests never reach the network (docs/09 section 2.2, D247): only loopback, for anything."""

import ipaddress
import socket
from typing import Any

import pytest

_LOCAL_NAMES = {"localhost", "testserver"}


class NetworkAccessError(RuntimeError):
    pass


def _is_local(host: Any) -> bool:
    if not isinstance(host, str):
        return True  # AF_UNIX paths and the like never leave the machine
    if host in _LOCAL_NAMES:
        return True
    try:
        return ipaddress.ip_address(host.split("%")[0]).is_loopback
    except ValueError:
        return False


@pytest.fixture(autouse=True)
def no_network(monkeypatch: pytest.MonkeyPatch) -> None:
    connect = socket.socket.connect
    connect_ex = socket.socket.connect_ex
    getaddrinfo = socket.getaddrinfo

    def guarded_connect(self: socket.socket, address: Any) -> None:
        host = address[0] if isinstance(address, tuple) else address
        if not _is_local(host):
            raise NetworkAccessError(f"a test tried to connect to {host}")
        connect(self, address)

    def guarded_connect_ex(self: socket.socket, address: Any) -> int:
        host = address[0] if isinstance(address, tuple) else address
        if not _is_local(host):
            raise NetworkAccessError(f"a test tried to connect to {host}")
        return connect_ex(self, address)

    def guarded_getaddrinfo(host: Any, *args: Any, **kwargs: Any) -> Any:
        if host is not None and not _is_local(host if isinstance(host, str) else host.decode()):
            raise NetworkAccessError(f"a test tried to resolve {host}")
        return getaddrinfo(host, *args, **kwargs)

    monkeypatch.setattr(socket.socket, "connect", guarded_connect)
    monkeypatch.setattr(socket.socket, "connect_ex", guarded_connect_ex)
    monkeypatch.setattr(socket, "getaddrinfo", guarded_getaddrinfo)
