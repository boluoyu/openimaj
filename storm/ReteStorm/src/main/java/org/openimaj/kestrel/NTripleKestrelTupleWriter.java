package org.openimaj.kestrel;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import org.apache.log4j.Logger;
import org.apache.thrift7.TException;
import org.openimaj.kestrel.writing.NTripleWritingScheme;
import org.openimaj.kestrel.writing.StringWritingScheme;
import org.openimaj.kestrel.writing.WritingScheme;
import org.openimaj.rdf.storm.spout.NTriplesSpout;
import org.openimaj.rdf.storm.utils.JenaStromUtils;
import org.openjena.atlas.lib.Sink;
import org.openjena.riot.RiotReader;
import org.openjena.riot.RiotWriter;
import org.openjena.riot.lang.LangNTriples;


import backtype.storm.Config;
import backtype.storm.spout.KestrelThriftClient;
import backtype.storm.tuple.Tuple;

import com.hp.hpl.jena.graph.Graph;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.sparql.util.graph.GraphFactory;
import com.sun.xml.internal.messaging.saaj.util.ByteOutputStream;

/**
 * This writer queues a set of RDF triples in a kestrel queue as storm
 * {@link Tuple} instances defined by the {@link WritingScheme} used. The
 * triples are written as NTriple strings by default, but other serialisations
 * can be specified
 * 
 * @author Jon Hare (jsh2@ecs.soton.ac.uk), Sina Samangooei (ss@ecs.soton.ac.uk)
 * 
 */
public class NTripleKestrelTupleWriter implements Sink<Triple> {

	protected final static Logger logger = Logger
			.getLogger(NTripleKestrelTupleWriter.class);

	private URL tripleSource;
	private WritingScheme scheme;
	private KestrelThriftClient client;
	private String queue;

	/**
	 * @param url
	 *            the source of triples
	 */
	public NTripleKestrelTupleWriter(URL url) {
		tripleSource = url;
		this.scheme = new NTripleWritingScheme();
	}

	/**
	 * Write the triples from the URL to the {@link KestrelServerSpec} to the
	 * queue
	 * 
	 * @param spec
	 * @param queue
	 * @throws TException
	 * @throws IOException
	 */
	public void write(KestrelServerSpec spec, String queue) throws TException,
			IOException {
		logger.debug("Opening kestrel client");
		this.client = new KestrelThriftClient(spec.host, spec.port);
		this.queue = queue;
		logger.debug("Deleting the old queue");
		client.delete_queue(queue);
		LangNTriples parser = RiotReader.createParserNTriples(tripleSource.openStream(), this);
		parser.parse();
		logger.debug("Finished parsing");
		// RiotReader.p
		// client.put(queue, this.scheme.serialize(NTriplesSpout.asValue(t)),
		// 0);

	}

	@Override
	public void close() {
		// logger.debug("Closing...");
		// logger.debug("Closed client");
		// client = null;
		// queue = null;
		// logger.debug("Done!");
	}

	@Override
	public void send(Triple item) {
		List<Object> tripleList = Arrays.asList((Object)item);
		byte[] serialised = this.scheme.serialize(tripleList);
		logger.debug("Writing triple: " + item);
		try {
			this.client.put(this.queue, Arrays.asList(ByteBuffer.wrap(serialised)), 0);
		} catch (TException e) {
			logger.error("Failed to add");
		}
	}

	@Override
	public void flush() {
		client.close();
		logger.debug("Queue flushed");
	}

}
