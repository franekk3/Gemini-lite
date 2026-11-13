import json, sys, urllib.request
import websocket

pages = json.load(urllib.request.urlopen('http://127.0.0.1:9222/json/list'))
page = next(p for p in pages if p.get('type') == 'page')
ws = websocket.create_connection(page['webSocketDebuggerUrl'], timeout=30, suppress_origin=True)
expr = sys.stdin.read()
ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {
    "expression": expr, "returnByValue": True, "awaitPromise": True, "userGesture": True}}))
while True:
    msg = json.loads(ws.recv())
    if msg.get('id') == 1:
        result = msg.get('result', {})
        if 'exceptionDetails' in result:
            print("EXCEPTION:", json.dumps(result['exceptionDetails'])[:2000])
        else:
            print(json.dumps(result.get('result', {}).get('value'), indent=2, ensure_ascii=False))
        break
