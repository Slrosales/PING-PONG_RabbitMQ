# RabbitMQ Ping-Pong - Distributed State Machine

**Authors:** GOMEZ ROSALES Laura Sofía

---

This project is based on a **State Machine** model that handles asynchronous communication, concurrency (race conditions), and failure transparency (node crashes).

This implementation solves the problem where two nodes might try to initiate the PING-PONG simultaneously, giving rise to a tie. The node with the smaller ID wins the tie and becomes the Pinger, while the other becomes the Ponger.

---

## Setup and Installation

1.  Java JDK 11+
2.  **Maven** for dependency management
3.  **Docker** to run the RabbitMQ server (can also be local or cloud instance)

---

### 1. Clone the Repository
```bash
git clone https://github.com/Slrosales/PING-PONG_Groupe_J.git
cd PING-PONG_Groupe_J
```

### 2. Start RabbitMQ (with Management UI)
We use Docker to run RabbitMQ.

```bash
docker run -d --hostname rabbit-host --name <container name> -p 5672:5672 -p 15672:15672 rabbitmq:3-management
```
*   **Port 5672:** Used by Java code to send messages
*   **Port 15672:** Used for the Web Dashboard

Go to [http://localhost:15672](http://localhost:15672) and log in with `guest` / `guest` to verify RabbitMQ is running.

### 3. Compile the Project
We use **Maven** to manage the RabbitMQ client library (`amqp-client`) and SLF4J logging dependencies defined in `pom.xml`.

```bash
mvn clean compile
```

---

## Project Structure

*   **`pom.xml`**: The Maven configuration file. It tells Maven to download the `com.rabbitmq.amqp-client` library to not manually manage `.jar` files
*   **`src/main/java/PingPongNode.java`**: It implements the State Machine logic based on the pseudo-code rules provided in class. It handles message sending/receiving, state transitions, and the tie-breaking logic
*   **`run_node1.sh` & `run_node2.sh`**: Bash scripts to quickly launch the nodes with pre-configured arguments

---

## The Algorithm

### State Machine
*   **States:** `NOT_STARTED`, `STARTED`, `S_PING` (Leader/Pinger), `S_PONG` (Follower/Ponger)
*   **Tie-Breaker:** If both nodes start simultaneously, the node with the **smaller ID** wins and becomes the Pinger

### Mapping Pseudo-code to Java

| Concept | Pseudo-code | Java Implementation |
| :--- | :--- | :--- |
| **Input** | `req ? START` | `node.triggerStart()` (User presses ENTER) |
| **Receive** | `in ? MSG` | `node.handleDelivery(message)` (RabbitMQ Callback) |
| **Send** | `out ! MSG` | `channel.basicPublish("", targetQueue, ...)` |
| **Atomicity** | Rules are atomic | `synchronized void handleDelivery(...)` |

### The Logic Rules
1.  **Rule 1:** User initiates Start -> Send `WANT_PING`.
2.  **Rule 2:** Receive `WANT_PING` (idle) -> Concede, become Ponger, send `GO_AHEAD`.
3.  **Rule 3 (Tie-Breaker):** Receive `WANT_PING` (while also started) -> Compare IDs. Smallest ID wins (becomes Pinger), Largest ID loses (becomes Ponger).
4.  **Rule 4-8:** Handle the transition to the active game loop.
5.  **Exception Rule (Crash Recovery):** If a node receives `WANT_PING` while already playing, it assumes the partner crashed and restarted. It resets its state and sends `GO_AHEAD`.

---

## Testing Scenarios

### Scenario A: The Standard Ping-Pong
1.  **Terminal 1:** Run `./run_node1.sh`
2.  **Terminal 2:** Run `./run_node2.sh`
3.  Press **ENTER** on **Terminal 1**
**Result:** Node 1 becomes Pinger, Node 2 becomes Ponger. They exchange messages indefinitely

### Scenario B: The Crash Recovery 
1.  Start the code as above
2.  Press `Ctrl+C` on **Node 1** to kill it
3.  **Node 2** will wait in silence (holding its state)
4.  Restart Node 1: `./run_node1.sh`
5.  Press **ENTER** on Node 1
**Result:** Node 2 detects the restart, prints `>>> EXCEPTION: Partner restarted!`, and the game resumes automatically without deadlocking

### Scenario C: The Race Condition 
To test Rule 3 (Tie-Breaker), both nodes must start at the exact same millisecond. We use a bash command to simulate this:

1.  Stop both nodes.
2.  Run this command in a single terminal:
    ```bash
    (sleep 5; echo ""; sleep 1000) | ./run_node1.sh & (sleep 5; echo ""; sleep 1000) | ./run_node2.sh & wait
    ```
**Result:** Both nodes trigger START simultaneously. You will see `RULE 3a` (I lost) and `RULE 3b` (I won) in the logs

---

## Monitoring with RabbitMQ UI

Go to the **Queues** tab in the RabbitMQ Management UI to see `queue1` and `queue2`. You can click on each queue to see real-time message rates and the number of messages ready/acknowledged

*   **Message Rates:** When the game is running, you will see the graph spike as messages flow in/out.
*   **Queue Depth (Ready Messages):** If you kill a node (`Ctrl+C`), you will see the "Ready" count increase (e.g., to 1). This is the "Ghost Message" waiting for the node to come back online. This demonstrates **Failure Transparency**
