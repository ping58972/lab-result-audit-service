"""Exercise the packaged Java entry point, real HTTP, and database reopen."""
import json
import os
from pathlib import Path
import secrets
import select
import subprocess
import tempfile
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def call(method, path, token, payload=None):
    body = None if payload is None else json.dumps(payload).encode()
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = Request("http://127.0.0.1:8091" + path, data=body, method=method, headers=headers)
    try:
        response = urlopen(request, timeout=5)
    except HTTPError as error:
        response = error
    with response:
        return response.status, json.load(response)


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def start(database, environment):
    process = subprocess.Popen(
        ["java", "-jar", str(ROOT / "target/lab-result-audit-service-1.0.0.jar"), str(database)],
        env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    ready, _, _ = select.select([process.stdout], [], [], 10)
    if not ready or not process.stdout.readline().startswith(b"Synthetic"):
        process.kill()
        _, error = process.communicate(timeout=5)
        raise RuntimeError("Service startup failed: " + error.decode())
    return process


def stop(process):
    process.terminate()
    try:
        process.communicate(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.communicate(timeout=5)


def main():
    operator, reviewer = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    environment = dict(os.environ, LAB_OPERATOR_TOKEN=operator, LAB_REVIEWER_TOKEN=reviewer)
    with tempfile.TemporaryDirectory(prefix="lab-audit-smoke-") as directory:
        database = Path(directory) / "results"
        process = start(database, environment)
        try:
            payload = {"operationId": "smoke-1", "sampleId": "SIM-A", "signal": 42.125, "qcPassed": True}
            check(call("POST", "/results", None, payload)[0] == 401, "Authentication check failed.")
            check(call("POST", "/results", operator, payload)[0] == 201, "Create failed.")
            check(call("POST", "/results", operator, payload)[0] == 200, "Replay failed.")
            check(call("POST", "/results/SIM-A/release", operator)[0] == 403, "Role check failed.")
            check(call("POST", "/results/SIM-A/release", reviewer)[0] == 200, "Release failed.")
            status, events = call("GET", "/results/SIM-A/audit", reviewer)
            check(status == 200 and [event["event"] for event in events] == ["CREATED", "RELEASED"],
                  "Audit mismatch.")
        finally:
            stop(process)
        process = start(database, environment)
        try:
            status, result = call("GET", "/results/SIM-A", operator)
            check(status == 200 and result["state"] == "RELEASED", "Persistence check failed.")
        finally:
            stop(process)
    print("PASS packaged JAR, authentication, create/replay, release, audit, and persistence across restart")


if __name__ == "__main__":
    main()
