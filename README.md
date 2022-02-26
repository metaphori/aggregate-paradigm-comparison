# Comparison of XC/Scala w.r.t. Akka actors and Vertx pub-sub

Please take a look at the following solutions for the self-healing channel problem:

- [Aggregate.scala](actors/src/main/scala/it/unibo/aggrcompare/Aggregate.scala)
- [PubSubVertxChannel](pubsub/src/main/scala/it/unibo/aggrcompare/PubSubVertxChannel.scala)
- [PubSubVertxAggregateLike.scala](pubsub/src/main/scala/it/unibo/aggrcompare/PubSubVertxAggregateLike.scala)
- Folder [comparison](/comparison) for a selection of relevant code snippets