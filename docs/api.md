# Mycelium engine API

All features go through `MyceliumEngine.call(method, argsJson)`, JSON in, JSON out.

Unknown method:
```
call("nope") -> {"error":"unknown_method"}
```
