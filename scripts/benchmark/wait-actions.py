#!/usr/bin/env python3
"""Wait outside the model loop; emit one terminal result for the harness callback.

This is low-frequency REST polling in a background process, not a GitHub push webhook.
It deliberately does not stream gh run watch progress into the assistant transcript.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument("--repo", required=True)
parser.add_argument("--head-sha", required=True)
parser.add_argument("--workflow", default="vt-benchmark.yml")
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--interval", type=int, default=30)
parser.add_argument("--timeout", type=int, default=6000)
args = parser.parse_args()
if not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repo) or not re.fullmatch(r"[0-9a-f]{40}", args.head_sha):
    parser.error("Explicit owner/repo and full commit SHA are required")
if not re.fullmatch(r"[\w.-]+\.ya?ml", args.workflow) or min(args.interval, args.timeout) <= 0:
    parser.error("Invalid workflow or wait interval")

started = time.monotonic()
requests = 0
run_id = None
while time.monotonic() - started < args.timeout:
    endpoint = (f"repos/{args.repo}/actions/runs/{run_id}" if run_id else
                f"repos/{args.repo}/actions/runs?head_sha={args.head_sha}&event=push&per_page=30")
    response = subprocess.run(["gh", "api", endpoint], capture_output=True, text=True, timeout=45, check=True)
    requests += 1
    payload = json.loads(response.stdout)
    if run_id is None:
        runs = [run for run in payload["workflow_runs"]
                if run["head_sha"] == args.head_sha
                and run["path"].split("@")[0] == ".github/workflows/" + args.workflow]
        if not runs:
            time.sleep(args.interval)
            continue
        payload = max(runs, key=lambda run: run["id"])
        run_id = payload["id"]
    if payload["head_sha"] != args.head_sha:
        raise RuntimeError("Run identity changed")
    if payload["status"] == "completed":
        result = {key: payload[key] for key in ["id", "head_sha", "status", "conclusion", "html_url", "run_attempt"]}
        result.update(rest_requests=requests, observer_elapsed_seconds=round(time.monotonic() - started, 1),
                      notification="single background-process completion callback; REST polling internally")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result), flush=True)
        break
    time.sleep(args.interval)
else:
    raise TimeoutError(f"Workflow {args.workflow} at {args.head_sha} did not complete; last run={run_id}")
