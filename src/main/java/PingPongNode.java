import com.rabbitmq.client.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class PingPongNode {

    enum State {
        NOT_STARTED, STARTED, S_PING, S_PONG
    }

    private State state = State.NOT_STARTED;
    private final int id;
    private final String myQueue;
    private final String targetQueue;
    private final Channel channel;

    public PingPongNode(int id, String myQueue, String targetQueue, Channel channel) {
        this.id = id;
        this.myQueue = myQueue;
        this.targetQueue = targetQueue;
        this.channel = channel;
    }

    // out ! msg
    private void sendMsg(String message) throws IOException {
        System.out.println("Sending: '" + message + "'");
        channel.basicPublish("", targetQueue, null, message.getBytes(StandardCharsets.UTF_8));
    }

    // RULE 1: req ? START
    // Triggered locally by the user pressing ENTER in terminal
    public synchronized void triggerStart() throws IOException {
        if (state == State.NOT_STARTED) {
            System.out.println("--> RULE 1: Local START triggered");
            sendMsg("WANT_PING:" + id);
            state = State.STARTED;
        }
    }

    // ASYNCHRONOUS MESSAGE RECEPTION ("in ? msg")
    public synchronized void handleDelivery(String message) throws IOException {
        System.out.println("Received: '" + message + "' | Current State: " + state);

        String[] parts = message.split(":");
        String msgType = parts[0];

        switch (msgType) {

            case "WANT_PING":
                int ID = Integer.parseInt(parts[1]); // Extract sender's ID

                // RULE 2: Receive WANT_PING and I haven't started
                if (state == State.NOT_STARTED) {
                    System.out.println("--> RULE 2: Conceding to " + ID);
                    sendMsg("GO_AHEAD");
                    state = State.S_PONG;
                }
                // RULE 3: We BOTH started. Tie-breaker!
                else if (state == State.STARTED) {
                    if (ID < id) {
                        System.out.println("--> RULE 3a: I lost tie-breaker  (larger ID)");
                        sendMsg("GO_AHEAD");
                        state = State.S_PONG;
                    } else {
                        System.out.println("--> RULE 3b: I won tie-breaker! (smaller ID)");
                        state = State.S_PING;
                        sendMsg("PING");
                    }
                }
                // If I am already playing, but the other guy sends WANT_PING,
                // it means he crashed and restarted. I will reset and concede.
                else if (state == State.S_PING || state == State.S_PONG) {
                    System.out.println("--> EXCEPTION: RESTART DETECTED! Resetting state and conceding");
                    sendMsg("GO_AHEAD");
                    state = State.S_PONG;
                }
                break;

            case "GO_AHEAD":
                // RULE 5: Receive GO_AHEAD but I already won (do nothing)
                if (state == State.S_PING) {
                    System.out.println("--> RULE 5: Received late GO_AHEAD, ignoring.");
                }
                // RULE 8: Receive GO_AHEAD and I was waiting
                else if (state == State.STARTED) {
                    System.out.println("--> RULE 8: Got permission to start game");
                    state = State.S_PING;
                    sendMsg("PING");
                }
                break;

            case "PING":
                // RULE 4: Receive PING while waiting (the other guy won tie-breaker and started)
                if (state == State.STARTED) {
                    System.out.println("--> RULE 4: Received PING while waiting. Becoming PONGER.");
                    state = State.S_PONG;
                    sendMsg("PONG");
                }
                // RULE 7: Normal PING/PONG loop (I am PONGER)
                else if (state == State.S_PONG) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) {} // Slower response to make logs clearer
                    System.out.println("--> RULE 7: Bouncing back PONG");
                    sendMsg("PONG");
                }
                break;

            case "PONG":
                // RULE 6: Normal PING/PONG loop (I am PINGER)
                if (state == State.S_PING) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) {} // Slower response to make logs clearer
                    System.out.println("--> RULE 6: Bouncing back PING");
                    sendMsg("PING");
                }
                break;
        }
    }

    public static void main(String[] argv) throws Exception {
        if (argv.length < 3) {
            System.out.println("Usage: java PingPongNode <my_id> <my_queue> <target_queue>");
            return;
        }

        int myId = Integer.parseInt(argv[0]);
        String myQueue = argv[1];
        String targetQueue = argv[2];

        // Connect to RabbitMQ (Docker on localhost)
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        // Declare queues
        channel.queueDeclare(myQueue, false, false, false, null);
        channel.queueDeclare(targetQueue, false, false, false, null);

        PingPongNode node = new PingPongNode(myId, myQueue, targetQueue, channel);

        // "in ?" listener
        System.out.println(" [*] Node " + myId + " listening on " + myQueue + ". Press ENTER to initiate START.");
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            // Synchronized handle block to ensure atomicity
            node.handleDelivery(message);
        };
        channel.basicConsume(myQueue, true, deliverCallback, consumerTag -> {
        });

        // Waiting for local user input to trigger Rule 1
        Scanner scanner = new Scanner(System.in);
        while (true) {
            scanner.nextLine(); // Waits for user to press ENTER
            node.triggerStart();
        }
    }
}