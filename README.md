Multi-threaded Chat Application with REST API (Core Java)
Console-based, real-time multi-client chat using Sockets and Multithreading, plus a REST API (JDK built-in HTTP server, no frameworks) to monitor and control the chat.

Files
ChatServer.java - accepts connections (one thread per client); also starts the REST API
ClientHandler.java - serves one client (login, commands, message relay)
ClientRegistry.java - thread-safe pool of connected clients and bounded chat history (synchronized)
ChatClient.java - console client (receiver thread + keyboard input)
ApiServer.java - REST API (HTTP + JSON) on com.sun.net.httpserver, 4-thread pool
JsonUtil.java - small JSON helper (string escaping, flat-object parsing)
Run
javac *.java
java ChatServer                 # chat port 5000, max 50 clients, REST API port 8080
java ChatServer 5000 20 9090    # chat port, max clients, REST API port
In separate terminals:

java ChatClient                 # localhost:5000
java ChatClient localhost 5000
Chat commands
/users - list online users
/quit - leave the chat
REST API (JSON)
Method	Endpoint	Description
GET	/api/status	Server status: online users, max clients, uptime
GET	/api/users	List online users
DELETE	/api/users/{name}	Remove (kick) a user from the chat
GET	/api/messages?limit=20	Recent messages (limit 1-100, default 20)
POST	/api/messages	Send a message to all chat users
POST body: {"from":"Bot","message":"Hello everyone"} (from is optional, default API; messages appear in the chat as [API] Bot: ...)

Status codes: 200 OK, 201 created, 400 bad input, 404 not found, 405 wrong method, 413 body too large. Errors look like {"error":"..."}.

Try it with curl
curl http://localhost:8080/api/status
curl http://localhost:8080/api/users
curl "http://localhost:8080/api/messages?limit=10"
curl -X POST http://localhost:8080/api/messages \
     -H "Content-Type: application/json" \
     -d '{"from":"Bot","message":"Server maintenance at 10 PM"}'
curl -X DELETE http://localhost:8080/api/users/bob
Features
Thread-per-client server: each client handled independently
Thread-safe broadcast: a message from one client is relayed to all others
Synchronized client registry: capped client limit, unique usernames, safe add/remove under concurrent access (chat threads and REST threads share it)
REST API over HTTP with JSON, input validation and proper status codes
