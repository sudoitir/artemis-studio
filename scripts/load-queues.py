#!/usr/bin/env python3
"""Create queues on one broker through Jolokia, in bulk requests.

usage: load-queues.py JOLOKIA_URL COUNT

Queue i is `load.q.<i>` on address `load.a.<i>`, anycast and durable. Rahdar's own API creates one queue per
call, each with an audit record and a broker read; thousands of queues for a load test go in through the
broker's management operations instead, which is what a real estate that grew over years looks like to Rahdar.
Re-running skips the queues that exist.
"""
import base64
import json
import sys
import urllib.request

url, count = sys.argv[1].rstrip("/"), int(sys.argv[2])
auth = "Basic " + base64.b64encode(b"artemis:artemis").decode()


def post(body):
    req = urllib.request.Request(
        url + "/",
        data=json.dumps(body).encode(),
        headers={"Authorization": auth, "Origin": "http://localhost", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=120) as response:
        return json.load(response)


broker = post({"type": "search", "mbean": "org.apache.activemq.artemis:broker=*"})["value"][0]
create = "createQueue(java.lang.String,java.lang.String,java.lang.String,java.lang.String,boolean,int,boolean,boolean)"
failed = 0
for start in range(0, count, 200):
    batch = [
        {
            "type": "exec",
            "mbean": broker,
            "operation": create,
            "arguments": [f"load.a.{i}", "ANYCAST", f"load.q.{i}", None, True, -1, False, True],
        }
        for i in range(start, min(start + 200, count))
    ]
    results = post(batch)
    if not isinstance(results, list):
        sys.exit(f"the broker answered a batch of queues with {str(results)[:300]}")
    for result in results:
        # A queue that exists is a re-run, not a failure.
        if result["status"] != 200 and "AMQ229019" not in result.get("error", "") and "exists" not in result.get("error", ""):
            failed += 1
            print(result.get("error", result), file=sys.stderr)
if failed:
    sys.exit(f"{failed} queues could not be created")
