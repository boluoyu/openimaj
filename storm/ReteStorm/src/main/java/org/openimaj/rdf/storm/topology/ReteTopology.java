package org.openimaj.rdf.storm.topology;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.log4j.Logger;
import org.apache.thrift7.TException;
import org.openimaj.kestrel.KestrelServerSpec;
import org.openimaj.kestrel.writing.NTripleWritingScheme;
import org.openimaj.rdf.storm.spout.NTriplesSpout;
import org.openimaj.rdf.storm.utils.JenaStromUtils;
import org.openimaj.storm.bolt.CountingEmittingBolt;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.generated.AlreadyAliveException;
import backtype.storm.generated.InvalidTopologyException;
import backtype.storm.generated.StormTopology;
import backtype.storm.spout.KestrelThriftSpout;
import backtype.storm.topology.BoltDeclarer;
import backtype.storm.topology.IRichBolt;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.tuple.Fields;
import backtype.storm.utils.Utils;

import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.reasoner.TriplePattern;
import com.hp.hpl.jena.reasoner.rulesys.Node_RuleVariable;
import com.hp.hpl.jena.reasoner.rulesys.Rule;

/**
 * Given a set of rules, construct a RETE topology such that filter (alpha)
 * nodes and join (beta) nodes are filtering bolts
 *
 * @author Sina Samangooei (ss@ecs.soton.ac.uk)
 *
 */
public class ReteTopology {
	/**
	 * The name of a debug bolt
	 */
	public static final String DEBUG_BOLT = "debugBolt";
	/**
	 * The name of the spout outputting triples
	 */
	public static final String TRIPLE_SPOUT = "tripleSpout";
	/**
	 * default rules
	 */
	public static final String RDFS_RULES = "/org/openimaj/rdf/rules/rdfs-fb-tgc-noresource.rules";
	/**
	 * the name of the final bolt
	 */
	public static final String FINAL_TERMINAL = "final_term";
	private static Logger logger = Logger.getLogger(ReteTopology.class);
;
	private int unnamedRules = 0;
	private InputStream rulesStream;

	/**
	 * Construct a Rete topology using the default RDFS rules
	 */
	public ReteTopology() {
		this.rulesStream = ReteTopology.class.getResourceAsStream(RDFS_RULES);
	}

	/**
	 * Construct a Rete topology using the InputStream as a source of rules
	 * @param rulesStream the stream of rules
	 */
	public ReteTopology(InputStream rulesStream) {
		this.rulesStream = rulesStream;
	}
	
	/**
	 * Using specified rules, construct a RETE storm topology
	 * @param nTriples A URL containing nTriples
	 *
	 * @return a storm topology
	 */
	public StormTopology buildTopology(String nTriples) {
		TopologyBuilder builder = new TopologyBuilder();
		NTriplesSpout tripleSpout = new NTriplesSpout(nTriples);
		builder.setSpout(TRIPLE_SPOUT, tripleSpout, 1);
//		builder.setBolt(DEBUG_BOLT, new PrintingBolt(), 1).shuffleGrouping(TRIPLE_SPOUT);
		compileCountingEmittingBolt(builder,tripleSpout.getFields());

		List<Rule> rules = loadRules();
//		ReteTopologyBuilder topologyBuilder = new SimpleReteTopologyBuilder();
//		topologyBuilder.compile(builder, TRIPLE_SPOUT, rules);
//		compileBolts(builder, TRIPLE_SPOUT, rules);

		return builder.createTopology();
	}
	
	/**
	 * Using specified rules, construct a RETE storm topology
	 * @param spec The kestrel server to which to connect
	 * @param inputQueue String outputQueue A kestrel queue containing triples
	 * @param outputQueue the name of the output queue
	 *
	 * @return a storm topology
	 */
	public StormTopology buildTopology(KestrelServerSpec spec, String inputQueue, String outputQueue) {
		
		TopologyBuilder builder = new TopologyBuilder();
//		NTriplesSpout tripleSpout = new NTriplesSpout(kestrelQueue);
		KestrelThriftSpout tripleSpout = new KestrelThriftSpout(spec.host, spec.port, inputQueue, new NTripleWritingScheme());
		
		builder.setSpout(TRIPLE_SPOUT, tripleSpout, 1);
//		builder.setBolt(DEBUG_BOLT, new PrintingBolt(), 1).shuffleGrouping(TRIPLE_SPOUT);
		
		List<Rule> rules = loadRules();
		ReteTopologyBuilder topologyBuilder = new KestrelReteTopologyBuilder();
		topologyBuilder.compile(builder, TRIPLE_SPOUT, rules);

		return builder.createTopology();
	}

	@SuppressWarnings("unused")
	private void compileCountingEmittingBolt(TopologyBuilder builder, Fields fields) {
		CountingEmittingBolt countingEmittingBolt1 = new CountingEmittingBolt(fields);
		builder.setBolt("blah1", countingEmittingBolt1, 1).shuffleGrouping(TRIPLE_SPOUT).shuffleGrouping("blah1");
	}

	/**
	 * Given a set of rules and a Storm {@link TopologyBuilder} compile a set of
	 * ReteBolts which encompass the filter, joins and actions of the rules
	 *
	 * @param builder
	 * @param rules
	 */
	private void compileBolts(TopologyBuilder builder, String source, List<Rule> rules) {
		
		ReteConflictSetBolt finalTerm = new ReteConflictSetBolt();
		BoltDeclarer finalTerminalBuilder = builder.setBolt(FINAL_TERMINAL, finalTerm);
		for (Rule rule : rules) {

			// These are the "alpha" nodes of the rule (the filters)
			Map<String, ReteFilterBolt> filters = new HashMap<String, ReteFilterBolt>();
			// This are the "beta" nodes of the rule (the joins)
			Map<String, ReteJoinBolt> joins = new HashMap<String, ReteJoinBolt>();
			// This is the terminal bolt (where the head is fired)
			ReteTerminalBolt term;

			int numVars = rule.getNumVars();

			// The rule name, bolts take the form of ruleName_(BODY|HEAD)_count
			String ruleName = rule.getName();
			if (ruleName == null)
				ruleName = nextRuleName();

			logger.debug(String.format("Compiling rule: %s",ruleName));
			// Extract all the filter clauses
			for (int i = 0; i < rule.bodyLength(); i++) {
				Object clause = rule.getBodyElement(i);
				if (clause instanceof TriplePattern) {
					String boltName = String.format("%s_filter_%d", ruleName, i);
//					ReteFilterBolt filterBolt = new ReteFilterBolt((TriplePattern) clause, numVars);
					ReteFilterBolt filterBolt = new ReteFilterBolt(rule,i);
					logger.debug(String.format("Filter bolt %s created from clause %s",boltName, clause));
					filters.put(boltName, filterBolt);
				}
			}

			// Now construct the beta networks (this could be optimised)
			String prior = null;
			boolean[] seenVar = new boolean[numVars];
			int joinNumber = 0;
			for (Entry<String, ReteFilterBolt> reteFilterBolt : filters.entrySet()) {
				String currentName = reteFilterBolt.getKey();
				ReteFilterBolt currentBolt = reteFilterBolt.getValue();
				List<Node> clauseVars = currentBolt.getClauseVars();

				// Get all the variable indecies which this join should worry
				// about
				ArrayList<Byte> matchIndices = new ArrayList<Byte>(numVars);
				for (Iterator<Node> iv = clauseVars.iterator(); iv.hasNext();) {
					int varIndex = ((Node_RuleVariable)iv.next()).getIndex();
					if (seenVar[varIndex])
						matchIndices.add(new Byte((byte) varIndex));
					seenVar[varIndex] = true;
				}

				if (prior == null) {
					logger.debug(String.format("Found the first filter node: %s, NO JOIN",currentName));
					prior = currentName;
				} else {
					logger.debug(String.format("Constructing join, left=%s, right=%s",prior,currentName));
					// Construct a ReteJoinBolt which knows which variables it
					// should map on
					String boltName = String.format("%s_join_%d", ruleName, joinNumber++);
					ReteJoinBolt reteJoinBolt = new ReteJoinBolt(prior, currentName, matchIndices);
					joins.put(boltName, reteJoinBolt);
					prior = boltName;
				}
			}

			if (prior != null) {
				term = new ReteTerminalBolt(rule);
			}else{
				continue; // This should never really happen, it insinuates an empty rule
			}


			// Now add the nodes to the actual topology
			for (Entry<String, ReteFilterBolt> nameFilter : filters.entrySet()) {
				String name = nameFilter.getKey();
				IRichBolt bolt = nameFilter.getValue();
				BoltDeclarer midBuild = builder.setBolt(name, bolt);
				// All the filter bolts are given triples from the source spout
				// and the final terminal
				midBuild.shuffleGrouping(source);
				midBuild.shuffleGrouping(FINAL_TERMINAL);
			}

			// We have a prior, we have a terminal bolt, we can go ahead and make addition to the topology
			String terminalName = String.format("%s_terminal",ruleName);
			builder.setBolt(terminalName, term).shuffleGrouping(prior);
			finalTerminalBuilder.shuffleGrouping(terminalName);





			for (Entry<String, ReteJoinBolt> join : joins.entrySet()) {
				String name = join.getKey();
				ReteJoinBolt bolt = join.getValue();
				BoltDeclarer midBuild = builder.setBolt(name, bolt, 1);
				midBuild.globalGrouping(bolt.getLeftBolt());
				midBuild.globalGrouping(bolt.getRightBolt());
			}
		}
	}

	private String nextRuleName() {
		unnamedRules += 1;
		return String.format("unnamed_rule_%d", unnamedRules);
	}

	private List<Rule> loadRules() {
		List<Rule> rules = Rule.parseRules(Rule.rulesParserFromReader(new BufferedReader(new InputStreamReader(this.rulesStream))));
		return rules;
	}

	/**
	 * run the rete topology
	 *
	 * @param args
	 * @throws InvalidTopologyException
	 * @throws AlreadyAliveException
	 * @throws IOException 
	 * @throws TException 
	 */
	public static void main(String args[]) throws AlreadyAliveException, InvalidTopologyException, TException, IOException {
		String rdfSource = "file:///Users/ss/Development/java/openimaj/trunk/storm/ReteStorm/src/test/resources/test.rdfs";
		String ruleSource = "/Users/ss/Development/java/openimaj/trunk/storm/ReteStorm/src/test/resources/test.rules";
		ReteTopology reteTopology = new ReteTopology(new FileInputStream(ruleSource));
//
		Config conf = new Config();
		conf.setDebug(false);
		conf.setNumWorkers(2);
		conf.setMaxSpoutPending(1);
		JenaStromUtils.registerSerializers(conf);
		LocalCluster cluster = new LocalCluster();
		StormTopology topology = reteTopology.buildTopology("file:///Users/ss/Development/java/openimaj/trunk/storm/ReteStorm/src/test/resources/test.rdfs");
//		KestrelServerSpec spec = KestrelServerSpec.localThrift();
//		String inputQueue = "triples";
//		String outputQueue = "processedTriples";
//		NTripleKestrelTupleWriter rdfWriter = new NTripleKestrelTupleWriter(new URL(rdfSource));
//		rdfWriter.write(spec, inputQueue);
//		StormTopology topology = reteTopology.buildTopology(spec, inputQueue, outputQueue);
		cluster.submitTopology("reteTopology", conf, topology);
		Utils.sleep(5000);
		cluster.killTopology("reteTopology");
		cluster.shutdown();

	}
}
