package com.nicta.scoobi
package impl
package plan
package mscr

import core._
import comp._
import util._
import CompNodes._
import exec.MapReduceJob
import scalaz.Equal
import impl.io.FileSystems
import org.apache.hadoop.fs.Path
import org.apache.hadoop.conf.Configuration
import rtt.TaggedKey
import mapreducer.{ChannelOutputFormat, UntaggedValues}

trait OutputChannel extends Channel {
  lazy val id: Int = UniqueId.get

  override def equals(a: Any) = a match {
    case o: OutputChannel => o.id == id
    case other            => false
  }
  override def hashCode = id.hashCode

  def sinks: Seq[Sink]
  def tag: Int

  def setup(implicit configuration: Configuration)
  def reduce[K, V, B](key: TaggedKey, untaggedValues: UntaggedValues[V], channelOutput: ChannelOutputFormat)(implicit configuration: Configuration)
  def cleanup(implicit configuration: Configuration)
  def collectOutputs(outputFiles: Seq[Path])(implicit configuration: ScoobiConfiguration, fileSystems: FileSystems) {
    val fs = configuration.fileSystem
    import fileSystems._

    sinks.zipWithIndex.foreach { case (sink, i) =>
      sink.outputPath foreach { outDir =>
        fs.mkdirs(outDir)
        val files = outputFiles filter isResultFile(tag, i)
        files foreach moveTo(outDir)
      }
    }
  }

}

trait MscrOutputChannel extends OutputChannel {
  def sinks = bridgeStore.toSeq ++ nodeSinks
  protected def nodeSinks: Seq[Sink]
  def bridgeStore: Option[Bridge]
}
case class GbkOutputChannel(groupByKey:   GroupByKey[_,_],
                            combiner: Option[Combine[_,_]]      = None,
                            reducer:  Option[ParallelDo[_,_,_]] = None) extends MscrOutputChannel {

  def tag = groupByKey.id
  def setup(implicit configuration: Configuration) { reducer.foreach(_.setup) }

  def reduce[K, V, B](key: TaggedKey, untaggedValues: UntaggedValues[V], channelOutput: ChannelOutputFormat)(implicit configuration: Configuration) {
    val emitter = new Emitter[B] {
      def emit(x: B)  {
        sinks.zipWithIndex foreach { case (sink, i) =>
          channelOutput.write(tag, i, sink.outputConverter.toKeyValue(x))
        }
      }
    }
    reducer.map(_.reduce(key.get(tag).asInstanceOf[K], untaggedValues, emitter)).getOrElse(emitter.emit((key.get(tag), untaggedValues)))
  }

  def cleanup(implicit configuration: Configuration) {
    groupByKey.cleanup(configuration)
    reducer.foreach(_.cleanup(configuration))
  }

  override def toString =
    Seq(Some(groupByKey),
        combiner.map(n => "combiner = "+n.toString),
        reducer .map(n => "reducer  = "+n.toString)
    ).flatten.mkString("GbkOutputChannel(", ", ", ")")

  override def equals(a: Any) = a match {
    case o: GbkOutputChannel => o.groupByKey.id == groupByKey.id
    case _                   => false
  }

  lazy val sinks = nodes.flatMap(_.sinks) :+ bridgeStore

  lazy val bridgeStore =
     (reducer: Option[CompNode]).
      orElse(combiner  ).
      getOrElse(groupByKey).bridgeStore
}

case class BypassOutputChannel(output: ParallelDo[_,_,_]) extends MscrOutputChannel {
  override def equals(a: Any) = a match {
    case o: BypassOutputChannel => o.output.id == output.id
    case _                      => false
  }
  def tag = output.id
  def sinks = output.sinks :+ bridgeStore
  lazy val bridgeStore = output.bridgeStore

  def setup(implicit configuration: Configuration) { output.setup }

  def reduce[K, V, B](key: TaggedKey, untaggedValues: UntaggedValues[V], channelOutput: ChannelOutputFormat)(implicit configuration: Configuration) {
    val emitter = new Emitter[B] {
      def emit(x: B)  {
        sinks.zipWithIndex foreach { case (sink, i) =>
          channelOutput.write(tag, i, sink.outputConverter.toKeyValue(x))
        }
      }
    }
    output.reduce(key.get(tag).asInstanceOf[K], untaggedValues, emitter)
  }

  def cleanup(implicit configuration: Configuration) {
    groupByKey.cleanup(configuration)
    reducer.foreach(_.cleanup(configuration))
  }


}

object OutputChannel {
  implicit def outputChannelEqual = new Equal[OutputChannel] {
    def equal(a1: OutputChannel, a2: OutputChannel) = a1.id == a2.id
  }
}

object Channels extends control.ImplicitParameters {
  /** @return a sequence of distinct mapper input channels */
  def distinct(ins: Seq[MapperInputChannel]): Seq[MapperInputChannel] =
    ins.map(in => (in.parDos.map(_.id), in)).toMap.values.toSeq

  /** @return a sequence of distinct group by key output channels */
  def distinct(out: Seq[GbkOutputChannel])(implicit p: ImplicitParam): Seq[GbkOutputChannel] =
    out.map(o => (o.groupByKey.id, o)).toMap.values.toSeq

  /** @return a sequence of distinct bypass output channels */
  def distinct(out: Seq[BypassOutputChannel])(implicit p1: ImplicitParam1, p2: ImplicitParam2): Seq[BypassOutputChannel] =
    out.map(o => (o.output.id, o)).toMap.values.toSeq
}
