# Compiles and runs Node 2 with ID=2, listening on queue2, sending to queue1
mvn compile exec:java -Dexec.mainClass="PingPongNode" -Dexec.args="2 queue2 queue1"