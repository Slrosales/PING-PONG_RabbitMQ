# Compiles and runs Node 1 with ID=1, listening on queue1, sending to queue2
mvn compile exec:java -Dexec.mainClass="PingPongNode" -Dexec.args="1 queue1 queue2"