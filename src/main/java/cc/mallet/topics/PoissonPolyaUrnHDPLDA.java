
package cc.mallet.topics;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.commons.math3.distribution.BinomialDistribution;
import org.apache.commons.math3.distribution.PoissonDistribution;

import cc.mallet.configuration.LDAConfiguration;
import cc.mallet.types.Dirichlet;
import cc.mallet.types.FeatureSequence;
import cc.mallet.types.InstanceList;
import cc.mallet.types.LabelSequence;
import cc.mallet.types.PolyaUrnDirichlet;
import cc.mallet.types.SparseDirichlet;
import cc.mallet.types.SparseDirichletSamplerBuilder;
import cc.mallet.types.VariableSelectionResult;
import cc.mallet.util.LDAUtils;
import cc.mallet.util.LoggingUtils;
import cc.mallet.util.OptimizedGentleAliasMethod;
import cc.mallet.util.WalkerAliasTable;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;

/**
 * This is a parallel implementation of the Poisson Polya Urn HDP
 * 
 * What this class adds on top of the PolyaUrn LDA is additional sampling
 * of the number of topics to use in each iteration. Since the number of
 * topics potentially change in each iteration we have to keep track of this.
 * It is not a problem when the number of topics increase, but when they
 * decrease we have to keep track of this and re-map topics with too high
 * topic indicator. This might be solvable in other ways more efficiently, 
 * by only setting the probability of those topics to zero in Phi this might
 * be implemented later.
 * 
 * The number of new topics are sampled just after the Z sampling in the 
 * postZ method. Here the typeTopic matrix is also updated.
 * 
 * In this particular version numTopics (possibly) change every iteration
 * and phi and the typeTopic matrix is changed to reflect numTopics 
 * 
 * @author Leif Jonsson
 *
 */
public class PoissonPolyaUrnHDPLDA extends UncollapsedParallelLDA implements HDPSamplerWithPhi {

	private static final long serialVersionUID = 1L;

	double gamma;
	double [] psi;
	List<Integer> activeTopics = new ArrayList<>();
	double alphaCoef;
	DocTopicTokenFreqTable docTopicTokenFreqTable; 
	int nrStartTopics;
	int maxTopics;
	List<Integer> activeTopicHistory = new ArrayList<Integer>();
	List<Integer> activeTopicInDataHistory = new ArrayList<Integer>();
	int [] topicOcurrenceCount;
	WalkerAliasTable [][] binomialTables;
	static final int BINOMIAL_TABLE_START_IDX = 50;
	static final int BINOMIAL_TABLE_SIZE = 50;
	static final int BINOMIAL_TABLE_MAXWIDTH = 200;
//	AtomicInteger countBernBin = new AtomicInteger();
//	AtomicInteger countBernSumBin = new AtomicInteger();
//	AtomicInteger countExactBin = new AtomicInteger();
//	AtomicInteger countAliasBin = new AtomicInteger();
//	AtomicInteger countNormalBin = new AtomicInteger();
	
	protected double[][] phitrans;

	WalkerAliasTable [] aliasTables; 
	double [] typeNorm; // Array with doubles with sum of alpha * phi
	private ExecutorService tableBuilderExecutor;

	// #### Sparsity handling
	// Jagged array containing the topics that are non-zero for each type
	int [][] nonZeroTypeTopicIdxs = null;
	// How many indices  are zero for each type, i.e the column count for the zeroTypeTopicIdxs array
	int [] nonZeroTypeTopicColIdxs = null;

	boolean staticPhiAliasTableIsBuild = false;

	public PoissonPolyaUrnHDPLDA(LDAConfiguration config) {
		super(config);
		
		gamma = config.getHDPGamma(LDAConfiguration.HDP_GAMMA_DEFAULT);
		nrStartTopics = config.getHDPNrStartTopics(LDAConfiguration.HDP_START_TOPICS_DEFAULT);
		
		System.out.println("HDP Start topics: " + nrStartTopics);
		
		// In the HDP the number of topics we are initialized with is 
		// taken as the maxNumber of topics possible
		maxTopics = numTopics;
		alphaCoef = config.getAlpha(LDAConfiguration.ALPHA_DEFAULT);
		
		psi = new double[numTopics];
		for (int i = 0; i < alpha.length; i++) {
			psi[i] = 1;
		}
		
		// We should NOT do hyperparameter optimization of alpha or beta in the HDP
		hyperparameterOptimizationInterval = -1;
		
		for (int i = 0; i < nrStartTopics; i++) {
			activeTopics.add(i);
		}
		docTopicTokenFreqTable = new DocTopicTokenFreqTable(numTopics);
	}
	
	WalkerAliasTable [][] initBinomialAlias(int maxDocLen, double gamma) {
		WalkerAliasTable [][] tables = new OptimizedGentleAliasMethod[BINOMIAL_TABLE_SIZE][BINOMIAL_TABLE_MAXWIDTH];
		for (int i = 0; i < BINOMIAL_TABLE_SIZE; i++) {
			for (int j = 0; j < BINOMIAL_TABLE_MAXWIDTH; j++) {
				double prob = gamma / (gamma + j);
				tables[i][j] = constructBinomialAliasTable(BINOMIAL_TABLE_START_IDX + i, prob);
			}
		}
		
		return tables;
	}
	
	static WalkerAliasTable constructBinomialAliasTable(int trials, double prob) {
		int tableLength = Math.max(100, 2*trials);
		WalkerAliasTable table = null;
		for (int i = 1; i < tableLength; i++) {
			BinomialDistribution binDistCalc = new BinomialDistribution(trials, prob);
			double [] trialProbabilities = new double [tableLength];
			for (int j = 0; j < tableLength; j++) {
				trialProbabilities[j] =  binDistCalc.probability(j);
			}
			table = new OptimizedGentleAliasMethod(trialProbabilities); 
		}
		return table;
	}
	
	@Override
	public void addInstances(InstanceList training) {
		alphabet = training.getDataAlphabet();
		numTypes = alphabet.size();
		nonZeroTypeTopicIdxs = new int[numTypes][numTopics];
		nonZeroTypeTopicColIdxs = new int[numTypes];
		topicOcurrenceCount = new int[numTopics];

		aliasTables = new WalkerAliasTable[numTypes];
		typeNorm    = new double[numTypes];
		phitrans    = new double[numTypes][numTopics];

		super.addInstances(training);
		
		// We now have longestDocLen
		binomialTables = initBinomialAlias(longestDocLength, gamma);
	}

	
	/* When we initialize Z we have to limit the topic indicators
	 * to nrStartTopics
	 * 
	 * @see cc.mallet.topics.UncollapsedParallelLDA#initialDrawTopicIndicator()
	 */
	@Override
	int initialDrawTopicIndicator() {
		return random.nextInt(nrStartTopics);
	}

	/* When we initialize phi we have to limit the topic indicators
	 * to nrStartTopics
	 */
	@Override
	public void initialSamplePhi(int [] topicIndices, double[][] phiMatrix) {
		int [] hdpStartTopicIndices = new int[nrStartTopics];
		for (int i = 0; i < nrStartTopics; i++) {
			topicIndices[i] = i;
		}
		super.initialSamplePhi(hdpStartTopicIndices, phi);
	}

	@Override
	public void preSample() {
		super.preSample();
		int poolSize = 2;
		tableBuilderExecutor = Executors.newFixedThreadPool(Math.max(1, poolSize));
		// Now all structures should be initialized with numTopics
		// now set numTopics to the number of topics we want to start with
	}

	protected SparseDirichlet createDirichletSampler() {
		SparseDirichletSamplerBuilder db = instantiateSparseDirichletSamplerBuilder(config.getDirichletSamplerBuilderClass("cc.mallet.types.PolyaUrnFixedCoeffPoissonDirichletSamplerBuilder"));
		return db.build(this);
	}

	class ParallelTableBuilder implements Callable<WalkerAliasTableBuildResult> {
		int type;
		public ParallelTableBuilder(int type) {
			this.type = type;
		}
		@Override
		public WalkerAliasTableBuildResult call() {
			double [] probs = new double[numTopics];
			double typeMass = 0; // Type prior mass
			double [] phiType =  phitrans[type]; 
			for (int topic = 0; topic < numTopics; topic++) {
				// In the HDP the sampled G takes the place of the alpha vector in LDA but
				// it is still multiplied with the LDA alpha scalar
				typeMass += probs[topic] = phiType[topic] * alphaCoef * psi[topic];
				if(phiType[topic]!=0) {
					int newSize = nonZeroTypeTopicColIdxs[type]++;
					nonZeroTypeTopicIdxs[type][newSize] = topic;
				}
			}

			// In HDP num topics keep changing, so so does probs, so
			// we have to completely re-build them... for now...
			//if(aliasTables[type]==null) {
				aliasTables[type] = new OptimizedGentleAliasMethod(probs,typeMass);
			//} else {
			//	aliasTables[type].reGenerateAliasTable(probs, typeMass);
			//}

			return new WalkerAliasTableBuildResult(type, aliasTables[type], typeMass);
		}   
	}

	@Override
	public void preIteration() {	
		doPreIterationTableBuilding();
		super.preIteration();
	}

	@Override
	public void postIteration() {
		//System.out.println("Freq table: \n" + docTopicTokenFreqTable);
		
		// Finish G sampling, i.e normalize G
		double sumG = 0.0;
		for (int i = 0; i < numTopics; i++) {			
			sumG += psi[i];
		}
		for (int i = 0; i < numTopics; i++) {			
			psi[i] /= sumG;
		}
		//System.out.println("Alpha G: " + Arrays.toString(alphaG));
		
		// Reset frequency table
		docTopicTokenFreqTable = new DocTopicTokenFreqTable(numTopics);
//		System.out.println("Exact: " + countExactBin.get() + " Normal: " + countNormalBin.get() + " Table: " + countAliasBin.get() + " Bern: " + countBernBin.get() + " BernSum: " + countBernSumBin.get());
	}
	
	protected void doPreIterationTableBuilding() {
		LDAUtils.transpose(phi, phitrans);

		List<ParallelTableBuilder> builders = new ArrayList<>();
		final int [][] topicTypeIndices = topicIndexBuilder.getTopicTypeIndices();
		if(topicTypeIndices!=null) {
			// The topicIndexBuilder supports having different types per topic,
			// this is currently not used, so we can just pick the first topic
			// since it will be the same for all topics
			int [] typesToSample = topicTypeIndices[0];
			for (int typeIdx = 0; typeIdx < typesToSample.length; typeIdx++) {
				builders.add(new ParallelTableBuilder(typesToSample[typeIdx]));
			}
			// if the topicIndexBuilder returns null it means sample ALL types
		} else {
			for (int type = 0; type < numTypes; type++) {
				builders.add(new ParallelTableBuilder(type));
			}			
		}

		List<Future<WalkerAliasTableBuildResult>> results;
		try {
			results = tableBuilderExecutor.invokeAll(builders);
			for (Future<WalkerAliasTableBuildResult> result : results) {
				aliasTables[result.get().type] = result.get().table;
				typeNorm[result.get().type] = result.get().typeNorm; // typeNorm is sigma_prior
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
			System.exit(-1);
		} catch (ExecutionException e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}

	public void preIterationGivenPhi() {
		if(!staticPhiAliasTableIsBuild) {
			doPreIterationTableBuilding();
			super.preIterationGivenPhi();
			staticPhiAliasTableIsBuild = true;
		}
	}

	@Override
	public void prePhi() {
		super.prePhi();
		Arrays.fill(nonZeroTypeTopicColIdxs,0);
	}
	
	/**
	 * Creates a table that maps the positions of active topics with topic to free
	 * slots in the topic range [0-nrActiveTopics]. This is used to return a dense
	 * representation of the type topic matrix and phi after sampling
	 * 
	 * @param numTopics
	 * @param newNumTopics
	 * @param activeTopics
	 * @return
	 */
	static protected Int2IntArrayMap createTopicTranslationTable(int numTopics, List<Integer> activeTopics, int [] topicOccurence) {
		Int2IntArrayMap translationTable = new Int2IntArrayMap();
		
		return translationTable;
	}

	/**
	 * Re-arranges the topics in the typeTopic matrix based
	 * on topicOccurence
	 * 
	 * @param topicMappingTable
	 */
	protected void reArrangeTopics(List<Integer> activeTopics,int [] topicOccurence) {

		for (int i = 0; i < topicOccurence.length; i++) {
			for (int j = 0; j < topicOccurence.length; j++) {
				if(topicOccurence[j]<topicOccurence[i]) {
					moveTopic(j, i);
					int tmpOccurence = topicOcurrenceCount[i]; 
					topicOcurrenceCount[i] = topicOcurrenceCount[j];
					topicOcurrenceCount[j] = tmpOccurence;
					
					// Update doc freq table
					docTopicTokenFreqTable.moveTopic(j,i);
				}
			}
		}
	}

	@Override
	public void postSample() {
		super.postSample();
		tableBuilderExecutor.shutdown();
		reArrangeTopics(activeTopics, topicOcurrenceCount);
	}
	
	interface GammaDist {
		int [] drawNewTopics(int nrSamples, int range);
	}
	
	class UniformGamma implements GammaDist {
		/**
		 * Draw new topic numbers from \Gamma
		 * 
		 * @param nrAddedTopics
		 * @param numTopics
		 * @return
		 */
		@Override
		public int[] drawNewTopics(int nrTopics, int range) {
			int [] newTopics = new int[nrTopics];
			for (int i = 0; i < newTopics.length; i++) {
				newTopics[i] = ThreadLocalRandom.current().nextInt(range); 
			}

			return newTopics;
		}		
	}

	@Override
	public void postZ() {
		super.postZ();
		
		// Resample the number of topics to use 
		activeTopicHistory.add(activeTopics.size());
		int activeInData = updateNrActiveTopics(docTopicTokenFreqTable.getEmptyTopics(), activeTopics, topicOcurrenceCount, numTopics);
		activeTopicInDataHistory.add(activeInData);
		//System.out.println("Active topics: " + Arrays.toString(activeTopics));
		//System.out.println("Nr Topics in data: " + activeInData);
		
		// Draw \nu
		int nrAddedTopics = sampleNrTopics(gamma);
		//System.out.println("nrAddedTopics: " + nrAddedTopics);
		
		// Draw new topic numbers from Gamma
		GammaDist gd = new UniformGamma();
		int [] topicNumbers = gd.drawNewTopics(nrAddedTopics, numTopics);
		//System.out.println("Sampled topics: " + Arrays.toString(topicNumbers));

		//System.out.println("Active topics before: " + activeTopics);
		// Calculate which if drawn topics where new
		int [] newTopics = calcNewTopics(activeTopics, topicNumbers);
		for (int i = 0; i < newTopics.length; i++) {
			activeTopics.add(newTopics[i]);
		}
		//System.out.println("New topics: " + Arrays.toString(newTopics));
		//System.out.println("Active topics after : " + activeTopics);
		
		int nrNewTopics = newTopics.length;
		int newNumTopics = activeTopics.size() + nrNewTopics;
		
		if(newNumTopics>maxTopics) 
			throw new IndexOutOfBoundsException("New sampled number of topics (" 
					+ newNumTopics 
					+ ") exceeds maxTopics (" + maxTopics + ") exiting");
				
		if (showTopicsInterval > 0 && currentIteration % showTopicsInterval == 0) {
			System.err.println("Topic stats: Active Topics:" + activeTopics.size() 
			+ "\t New topics: " + newNumTopics 
			+ "\t Active in data: " + activeInData 
			+ "\t Topic diff: " + (activeTopicHistory.get(activeTopicHistory.size()-1) - activeTopics.size()));
		}

		//System.out.println("New num topics: " + newNumTopics);
		// Add one to each of the newly drawn topics
		for (int i = 0; i < topicNumbers.length; i++) {
			psi[topicNumbers[i]]++;
		}
	}
	
	/** 
	 * Calculate which of the newly sampled topics are actually new. Only returns
	 * unique indices, so if topic is new 6 occurs twice in topic numbers, it will
	 * still only occur once in the output 
	 * 
	 * @param activeTopics
	 * @param topicNumbers
	 * @return array of not previously existing topics
	 */
	private int[] calcNewTopics(List<Integer> activeTopics, int[] topicNumbers) {
		Set<Integer> topicSack = new TreeSet<Integer>();
		for (int i = 0; i < topicNumbers.length; i++) {
			if(!activeTopics.contains(topicNumbers[i])) {
				topicSack.add(topicNumbers[i]); 
			}
		}
		int [] newTopics = new int[topicSack.size()];
		int i = 0;
		for (Integer topic : topicSack) {
			newTopics[i++] = topic;
		}
		return newTopics;
	}
	
	/* 
	 * Uses AD-LDA logLikelihood calculation
	 *  
	 * Here we override SimpleLDA's original likelihood calculation and use the
	 * AD-LDA logLikelihood calculation. 
	 * With this approach all models likelihoods are calculated the same way
	 */
	@Override
	public double modelLogLikelihood() {
		double logLikelihood = 0.0;
		//int nonZeroTopics;

		// The likelihood of the model is a combination of a 
		// Dirichlet-multinomial for the words in each topic
		// and a Dirichlet-multinomial for the topics in each
		// document.

		// The likelihood function of a dirichlet multinomial is
		//	 Gamma( sum_i alpha_i )	 prod_i Gamma( alpha_i + N_i )
		//	prod_i Gamma( alpha_i )	  Gamma( sum_i (alpha_i + N_i) )

		// So the log likelihood is 
		//	logGamma ( sum_i alpha_i ) - logGamma ( sum_i (alpha_i + N_i) ) + 
		//	 sum_i [ logGamma( alpha_i + N_i) - logGamma( alpha_i ) ]

		// Do the documents first

		int[] topicCounts = new int[numTopics];
		double[] topicLogGammas = new double[numTopics];
		int[] docTopics;

		for (int topic=0; topic < numTopics; topic++) {
			topicLogGammas[ topic ] = Dirichlet.logGammaStirling( alpha[topic] );
		}

		for (int doc=0; doc < data.size(); doc++) {
			LabelSequence topicSequence =	(LabelSequence) data.get(doc).topicSequence;

			docTopics = topicSequence.getFeatures();
						
			for (int token=0; token < docTopics.length; token++) {
				topicCounts[ docTopics[token] ]++;
			}

			for (int topic=0; topic < numTopics; topic++) {
				if (topicCounts[topic] > 0) {
					logLikelihood += (Dirichlet.logGammaStirling(alpha[topic] + topicCounts[topic]) -
							topicLogGammas[ topic ]);
				}
			}

			// subtract the (count + parameter) sum term
			logLikelihood -= Dirichlet.logGammaStirling(alphaSum + docTopics.length);

			Arrays.fill(topicCounts, 0);
		}

		// add the parameter sum term
		logLikelihood += data.size() * Dirichlet.logGammaStirling(alphaSum);

		// And the topics

		// Count the number of type-topic pairs that are not just (logGamma(beta) - logGamma(beta))
		int nonZeroTypeTopics = 0;

		for (int type=0; type < numTypes; type++) {
			// reuse this array as a pointer

			topicCounts = typeTopicCounts[type];

			for (int topic = 0; topic < numTopics; topic++) {
				if (topicCounts[topic] == 0) { continue; }

				nonZeroTypeTopics++;
				logLikelihood += Dirichlet.logGammaStirling(beta + topicCounts[topic]);

				if (Double.isNaN(logLikelihood)) {
					System.err.println("NaN in log likelihood calculation: " + topicCounts[topic]);
					System.exit(1);
				} 
				else if (Double.isInfinite(logLikelihood)) {
					logger.warning("infinite log likelihood");
					System.exit(1);
				}
			}
		}

		for (int topic=0; topic < numTopics; topic++) {
			logLikelihood -= 
					Dirichlet.logGammaStirling( (beta * numTypes) +
							tokensPerTopic[ topic ] );

			if (Double.isNaN(logLikelihood)) {
				logger.info("NaN after topic " + topic + " " + tokensPerTopic[ topic ]);
				return 0;
			}
			else if (Double.isInfinite(logLikelihood)) {
				logger.info("Infinite value after topic " + topic + " " + tokensPerTopic[ topic ]);
				return 0;
			}

		}

		// logGamma(|V|*beta) for every topic
		logLikelihood += 
				Dirichlet.logGammaStirling(beta * numTypes) * numTopics;

		// logGamma(beta) for all type/topic pairs with non-zero count
		logLikelihood -=
				Dirichlet.logGammaStirling(beta) * nonZeroTypeTopics;

		if (Double.isNaN(logLikelihood)) {
			logger.info("at the end");
		}
		else if (Double.isInfinite(logLikelihood)) {
			logger.info("Infinite value beta " + beta + " * " + numTypes);
			return 0;
		}

		return logLikelihood;
	}
	
	@Override
	protected double [] sampleTopicAssignmentsParallel(LDADocSamplingContext ctx) {
		FeatureSequence tokens = ctx.getTokens();
		LabelSequence topics = ctx.getTopics();
		int myBatch = ctx.getMyBatch();

		int type, oldTopic, newTopic;

		final int docLength = tokens.getLength();
		if(docLength==0) return null;

		int [] tokenSequence = tokens.getFeatures();
		int [] oneDocTopics = topics.getFeatures();

		double[] localTopicCounts = new double[numTopics];

		// This vector contains the indices of the topics with non-zero entries.
		// It has to be numTopics long since the non-zero topics come and go...
		int [] nonZeroTopics = new int[numTopics];

		// So we can map back from a topic to where it is in nonZeroTopics vector
		int [] nonZeroTopicsBackMapping = new int[numTopics];

		// Populate topic counts
		int nonZeroTopicCnt = 0;
		for (int position = 0; position < docLength; position++) {
			int topicInd = oneDocTopics[position];
			localTopicCounts[topicInd]++;
			if(localTopicCounts[topicInd]==1) {
				nonZeroTopicCnt = insert(topicInd, nonZeroTopics, nonZeroTopicsBackMapping, nonZeroTopicCnt);
			}
		}

		//kdDensities[myBatch] += nonZeroTopicCnt;
		kdDensities.addAndGet(nonZeroTopicCnt);

		double sum; // sigma_likelihood
		double[] cumsum = new double[numTopics]; 
		int [] nonZeroTopicsAdjusted;
		int nonZeroTopicCntAdjusted;

		//	Iterate over the words in the document
		for (int position = 0; position < docLength; position++) {
			type = tokenSequence[position];
			oldTopic = oneDocTopics[position]; // z_position
			localTopicCounts[oldTopic]--;

			// Potentially update nonZeroTopics mapping
			if(localTopicCounts[oldTopic]==0) {
				nonZeroTopicCnt = remove(oldTopic, nonZeroTopics, nonZeroTopicsBackMapping, nonZeroTopicCnt);
			}

			if(localTopicCounts[oldTopic]<0) 
				throw new IllegalStateException("Counts cannot be negative! Count for topic:" 
						+ oldTopic + " is: " + localTopicCounts[oldTopic]);

			// Propagates the update to the topic-token assignments
			/**
			 * Used to subtract and add 1 to the local structure containing the number of times
			 * each token is assigned to a certain topic. Called before and after taking a sample
			 * topic assignment z
			 */
			decrement(myBatch, oldTopic, type);
			//System.out.println("(Batch=" + myBatch + ") Decremented: topic=" + oldTopic + " type=" + type + " => " + batchLocalTopicUpdates[myBatch][oldTopic][type]);

			int nonZeroTypeCnt = nonZeroTypeTopicColIdxs[type];

			/*nonZeroTopicCntAdjusted = intersection(zeroTypeTopicIdxs[type], nonZeroTypeCnt, 
						nonZeroTopics, nonZeroTopicsBackMapping, nonZeroTopicsAdjusted, nonZeroTopicCnt);	

				String logstr = "Type NZ    : " + intVectorToString(zeroTypeTopicIdxs[type], fillCnt) 
						+ "\nDoc NZ     : " + intVectorToString(nonZeroTopics, nonZeroTopicCnt) 
						+ "\nAdjusted NZ: " + intVectorToString(nonZeroTopicsAdjusted, nonZeroTopicCntAdjusted);
				System.out.println(logstr);

				System.out.println("Type: " + fillCnt + " Topic: " + nonZeroTopicCnt + " Adjusted: " + nonZeroTopicCntAdjusted);
				if(nonZeroTopicCntAdjusted < Math.min(fillCnt, nonZeroTopicCnt)) {
					System.out.println("################### YAY!");
				}*/

			if(nonZeroTypeCnt < nonZeroTopicCnt) {
				// INTERSECTION SHOULD IMPROVE perf since we use result both in cumsum and sample topic
				// Intersection needs to b O(k) for it to improve perf, but unless we add more memory 
				// requirements it becomes O(k log(k))
				nonZeroTopicsAdjusted = nonZeroTypeTopicIdxs[type];
				nonZeroTopicCntAdjusted = nonZeroTypeCnt;
				//usedTypeSparsness.incrementAndGet();
			} else {
				nonZeroTopicsAdjusted = nonZeroTopics;
				nonZeroTopicCntAdjusted = nonZeroTopicCnt;
			}

			double u = ThreadLocalRandom.current().nextDouble();

			// Document and type sparsity removed all (but one?) topics, just use the prior contribution
			if(nonZeroTopicCntAdjusted==0) {
				newTopic = (int) Math.floor(u * numTopics); // uniform (0,1)
			} else {
				double [] phiType =  phitrans[type]; 
				int topic = nonZeroTopicsAdjusted[0];
				double score = localTopicCounts[topic] * phiType[topic];
				cumsum[0] = score;
				// Now calculate and add up the scores for each topic for this word
				// We build a cumsum indexed by topicIndex
				int topicIdx = 1;
				while ( topicIdx < nonZeroTopicCntAdjusted ) {
					topic = nonZeroTopicsAdjusted[topicIdx];
					score = localTopicCounts[topic] * phiType[topic];
					cumsum[topicIdx] = score + cumsum[topicIdx-1];
					topicIdx++;
				}
				sum = cumsum[topicIdx-1]; // sigma_likelihood

				// Choose a random point between 0 and the sum of all topic scores
				// The thread local random performs better in concurrent situations 
				// than the standard random which is thread safe and incurs lock 
				// contention
				double u_sigma = u * (typeNorm[type] + sum);
				// u ~ U(0,1)  
				// u [0,1]
				// u_sigma = u * (typeNorm[type] + sum)
				// if u_sigma < typeNorm[type] -> prior
				// u * (typeNorm[type] + sum) < typeNorm[type] => u < typeNorm[type] / (typeNorm[type] + sum)
				// else -> likelihood
				// u_prior = u_sigma / typeNorm[type] -> u_prior (0,1)
				// u_likelihood = (u_sigma - typeNorm[type]) / sum  -> u_likelihood (0,1)

				newTopic = sampleNewTopic(type, nonZeroTopicsAdjusted, nonZeroTopicCntAdjusted, sum, cumsum, u, u_sigma);
			}

			// Make sure we actually sampled a valid topic
			if (newTopic < 0 || newTopic >= numTopics) {
				throw new IllegalStateException ("Poisson Polya Urn HDP: New valid topic not sampled (" + newTopic + ").");
			}

			// Put that new topic into the counts
			oneDocTopics[position] = newTopic;
			localTopicCounts[newTopic]++;

			// Potentially update nonZeroTopics mapping
			if(localTopicCounts[newTopic]==1) {
				nonZeroTopicCnt = insert(newTopic, nonZeroTopics, nonZeroTopicsBackMapping, nonZeroTopicCnt);
			}

			// Propagates the update to the topic-token assignments
			/**
			 * Used to subtract and add 1 to the local structure containing the number of times
			 * each token is assigned to a certain topic. Called before and after taking a sample
			 * topic assignment z
			 */
			increment(myBatch, newTopic, type);
			
		}

		// Update the document topic count table
		for (int topic = 0; topic < numTopics; topic++) {
			if(localTopicCounts[topic]!=0) {
				docTopicTokenFreqTable.increment(topic,(int)localTopicCounts[topic]);
			}
		}

		return localTopicCounts;
	}

	double calcCumSum(int type, double[] localTopicCounts, int[] nonZeroTopics, int nonZeroTopicCnt, double[] cumsum) {
		double [] phiType =  phitrans[type]; 
		int topic = nonZeroTopics[0];
		double score = localTopicCounts[topic] * phiType[topic];
		cumsum[0] = score;
		// Now calculate and add up the scores for each topic for this word
		// We build a cumsum indexed by topicIndex
		int topicIdx = 1;
		while ( topicIdx < nonZeroTopicCnt ) {
			topic = nonZeroTopics[topicIdx];
			score = localTopicCounts[topic] * phiType[topic];
			cumsum[topicIdx] = score + cumsum[topicIdx-1];
			topicIdx++;
		}
		return cumsum[topicIdx-1];
	}

	/*
	 * Sample a topic indicator
	 * 
	 * @param type Type of the current token to sample
	 * @param nonZeroTopics Indices of the topics with p(z=k|.) > 0
	 * @param nonZeroTopicCnt Number of indicies in nonZeroTopics
	 * @param sum The sum of Sum_{nonzero_topic} localTopicCounts[topic] * phiType[topic] (also cumsum[nonZeroTopicCnt-1])
	 * @param cumsum The cumulative sum over Sum_{nonzero_topic} localTopicCounts[topic] * phiType[topic]
	 * @param u Uniform value within (0,1)
	 * @param u_sigma Same uniform value within (0,(typeNorm[type] + sum))
	 * 
	 * @return 
	 * 
	 */
	int sampleNewTopic(int type, int[] nonZeroTopics, int nonZeroTopicCnt, double sum, double[] cumsum, double u,
			double u_sigma) {
		int newTopic;
		if(u < (typeNorm[type]/(typeNorm[type] + sum))) {
			//numPrior++;
			newTopic = aliasTables[type].generateSample(u+((sum*u)/typeNorm[type])); // uniform (0,1)
			//System.out.println("Prior Sampled topic: " + newTopic);
		} else {
			//numLikelihood++;
			//double u_lik = (u_sigma - typeNorm[type]) / sum; // Cumsum is not normalized so don't divide by sum 
			double u_lik = (u_sigma - typeNorm[type]);
			int slot = SpaliasUncollapsedParallelLDA.findIdx(cumsum,u_lik,nonZeroTopicCnt);
			newTopic = nonZeroTopics[slot];
			// Make sure we actually sampled a valid topic
		}
		return newTopic;
	}

	protected static int removeIfIn(int oldTopic, int[] nonZeroTopics, int[] nonZeroTopicsBackMapping, int nonZeroTopicCnt) {
		if (nonZeroTopicCnt<1) {
			return nonZeroTopicCnt;
		}
		// We have one less non-zero topic, move the last to its place, and decrease the non-zero count
		int nonZeroIdx = nonZeroTopicsBackMapping[oldTopic];
		if( nonZeroIdx == 0 &&  nonZeroTopics[nonZeroIdx] != oldTopic) {
			return nonZeroTopicCnt; 
		} else {
			nonZeroTopics[nonZeroIdx] = nonZeroTopics[--nonZeroTopicCnt];
			nonZeroTopicsBackMapping[nonZeroTopics[nonZeroIdx]] = nonZeroIdx;
			return nonZeroTopicCnt;
		}
	}


	protected static int remove(int oldTopic, int[] nonZeroTopics, int[] nonZeroTopicsBackMapping, int nonZeroTopicCnt) {
		if (nonZeroTopicCnt<1) {
			throw new IllegalArgumentException ("SpaliasUncollapsedParallelLDA: Cannot remove, count is less than 1 => " + nonZeroTopicCnt);
		}
		// We have one less non-zero topic, move the last to its place, and decrease the non-zero count
		int nonZeroIdx = nonZeroTopicsBackMapping[oldTopic];
		//nonZeroTopicsBackMapping[oldTopic] = NOT_IN_SET;
		nonZeroTopics[nonZeroIdx] = nonZeroTopics[--nonZeroTopicCnt];
		nonZeroTopicsBackMapping[nonZeroTopics[nonZeroIdx]] = nonZeroIdx;
		return nonZeroTopicCnt;
	}

	protected static int insert(int newTopic, int[] nonZeroTopics, int[] nonZeroTopicsBackMapping, int nonZeroTopicCnt) {
		//// We have a new non-zero topic put it in the last empty slot and increase the count
		nonZeroTopics[nonZeroTopicCnt] = newTopic;
		nonZeroTopicsBackMapping[newTopic] = nonZeroTopicCnt;
		return ++nonZeroTopicCnt;
	}

	protected static int removeSorted(int oldTopic, int[] nonZeroTopics, int[] nonZeroTopicsBackMapping, int nonZeroTopicCnt) {
		if (nonZeroTopicCnt<1) {
			throw new IllegalArgumentException ("PolyaUrnLDA: Cannot remove, count is less than 1");
		}
		//System.out.println("New empty topic. Cnt = " + nonZeroTopicCnt);	
		int nonZeroIdx = nonZeroTopicsBackMapping[oldTopic];
		nonZeroTopicCnt--;
		// Shift the ones above one step to the left
		for(int i=nonZeroIdx; i<nonZeroTopicCnt;i++) {
			// Move the last non-zero topic to this new empty slot 
			nonZeroTopics[i] = nonZeroTopics[i+1];
			// Do the corresponding for the back mapping
			nonZeroTopicsBackMapping[nonZeroTopics[i]] = i;
		}
		return nonZeroTopicCnt;
	}

	protected static int insertSorted(int newTopic, int[] nonZeroTopics, int[] nonZeroTopicsBackMapping, int nonZeroTopicCnt) {
		//// We have a new non-zero topic put it in the last empty slot
		int slot = 0;
		while(newTopic > nonZeroTopics[slot] && slot < nonZeroTopicCnt) slot++;

		for(int i=nonZeroTopicCnt; i>slot;i--) {
			// Move the last non-zero topic to this new empty slot 
			nonZeroTopics[i] = nonZeroTopics[i-1];
			// Do the corresponding for the back mapping
			nonZeroTopicsBackMapping[nonZeroTopics[i]] = i;
		}				
		nonZeroTopics[slot] = newTopic;
		nonZeroTopicsBackMapping[newTopic] = slot;
		nonZeroTopicCnt++;
		return nonZeroTopicCnt;
	}	

	/**
	 * Samples new Phi's.
	 * 
	 * @param indices
	 * @param topicTypeIndices
	 * @param phiMatrix
	 */
	@Override
	public void loopOverTopics(int [] indices, int[][] topicTypeIndices, double[][] phiMatrix) {
		int [] activeIndices = new int[indices.length];
		int numActive = 0;
		for (int topic : indices) {
			// Set this topic to zero if it is inactive
			if(activeTopics.contains(topic)) {
				activeIndices[numActive++] = topic;
			}
		}
		
		long beforeSamplePhi = System.currentTimeMillis();		
		for (int topicIdx = 0; topicIdx < numActive; topicIdx++) {
			int topic = activeIndices[topicIdx];
			topicOcurrenceCount[topic]++;
			// First part of G sampling, rest (normalization) must be done 
			// in postIteration when all G_k has been sampled
			double l_k = sampleL(topic, gamma, longestDocLength, docTopicTokenFreqTable);
			//System.out.println("l_" + topic + " = " + l_k);
			
			// For new (not sampled by Z sampling) topics, the frequency will be 0, and l_k 
			// will also be zero, but for those topics we have already added 1 to psi in 
			// postZ
			int eta_k = 0; 
			if(l_k>0) {
				if(l_k>100) {
					eta_k = (int) PolyaUrnDirichlet.nextPoissonNormalApproximation(l_k);
				} else {				
					PoissonDistribution pois_gamma = new PoissonDistribution(l_k);
					eta_k = pois_gamma.sample();
				}
			}
			
			psi[topic] += eta_k;
			
			int [] relevantTypeTopicCounts = topicTypeCountMapping[topic];
			VariableSelectionResult res = dirichletSampler.nextDistributionWithSparseness(relevantTypeTopicCounts);
			
			phiMatrix[topic] = res.getPhi();
		}
		long elapsedMillis = System.currentTimeMillis();
		long threadId = Thread.currentThread().getId();

		if(measureTimings) {
			PrintWriter pw = LoggingUtils.checkCreateAndCreateLogPrinter(
					config.getLoggingUtil().getLogDir() + "/timing_data",
					"thr_" + threadId + "_Phi_sampling.txt");
			pw.println(beforeSamplePhi + "," + elapsedMillis);
			pw.flush();
			pw.close();
		}
	}

	protected int sampleL(int topic, double gamma, int maxDocLen, DocTopicTokenFreqTable docTopicTokenFreqTable) {
		int [] freqHist = docTopicTokenFreqTable.getReverseCumulativeSum(topic);
		
		// Sum over c_j_k
		int lSum = 0;
		for(int docLength = 0; docLength < maxDocLen; docLength++) {
			int trials = 0;
			if( freqHist.length > docLength ) {				
				trials = freqHist[docLength];
			}
			// As soon as we see zero, we know the rest will be 
			// zero and not contribute to the sum, so we can exit.
			if(trials==0) break;
			int bsample = 0;
			// Only sample if trials != 0, otherwise sample = 0;
			if(trials != 0) {
				int binomialAliasEndIdx = BINOMIAL_TABLE_START_IDX + BINOMIAL_TABLE_SIZE;

				// We don't have -1 here since we are 0-indexed
				double p = gamma / (gamma + docLength);
				
				if(trials == 1) {
//					countBernBin.incrementAndGet();
					bsample = ThreadLocalRandom.current().nextDouble() < p ? 1 : 0;
					// If suitable, use normal approximation to binomial
				} else if(trials * p >= 5 && trials * (1-p) >= 5) {
//					countNormalBin.incrementAndGet();
					//System.out.println("Normal approx");
					double meanNormal = trials * p;
					double variance = trials * p * (1-p); 
					bsample = (int) Math.round(Math.sqrt(variance) * ThreadLocalRandom.current().nextGaussian() + meanNormal);
					// If the trials is less than the binomial alias table, use sums of Bernoulli	
				} else if(trials < BINOMIAL_TABLE_START_IDX) {
//					countBernSumBin.incrementAndGet();
					for(int numTrials = 0; numTrials < trials; numTrials++) {						
						bsample += ThreadLocalRandom.current().nextDouble() < p ? 1 : 0;
					}
					// If, none of the above, draw from alias table
				} else if(trials > BINOMIAL_TABLE_START_IDX && trials < binomialAliasEndIdx && docLength < BINOMIAL_TABLE_MAXWIDTH){
//					countAliasBin.incrementAndGet();
//					System.out.println("Alias table");
					bsample = binomialTables[trials-BINOMIAL_TABLE_START_IDX][docLength].generateSample();
				// And as a last resort, use exact Binomials. TODO: Look for faster implementation than Apache
				} else {
//					countExactBin.incrementAndGet();
//					System.out.println("Exact: trials: " + trials + "\t" + docLength);
					BinomialDistribution c_j_k = new BinomialDistribution(trials, p);
					bsample = c_j_k.sample();
				}
			}
			//System.err.println("Binomial sample: Trials: " + trials + " probability: " + p + " => " + bsample);
			lSum += bsample;
		}
		return lSum;
	}	
	
	protected int sampleNrTopics(double gamma) {
		int sample = -1;
		if(gamma<1000) {
			PoissonDistribution pois_gamma = new PoissonDistribution(gamma);
			sample = pois_gamma.sample();
		} else {
			long lsample = PolyaUrnDirichlet.nextPoissonNormalApproximation(gamma);
			if(lsample>Integer.MAX_VALUE) throw new IllegalArgumentException("Nr topics sampled is TOOO large");
			sample = (int) lsample;
		}
		//System.out.println("Sampled: " + sample + " additional topics...");
		return sample; 
	}
	
	@Override
	public LDAConfiguration getConfiguration() {
		return config;
	}

	@Override
	public int getNoTypes() {
		return numTypes;
	}

	protected int updateNrActiveTopics(int[] emptyTopics, List<Integer> activeTopics, int[] topicOcurrenceCount, int numTopics) {
		for (int i = 0; i < emptyTopics.length; i++) {
			if(activeTopics.contains(emptyTopics[i])) {
				int idx = activeTopics.indexOf(emptyTopics[i]);
				activeTopics.remove(idx);
			}
		}
		return activeTopics.size();
	}

	public int[] getTopicOcurrenceCount() {
		return topicOcurrenceCount;
	}

	public void setTopicOcurrenceCount(int[] topicOcurrenceCount) {
		this.topicOcurrenceCount = topicOcurrenceCount;
	}

	public List<Integer> getActiveTopicHistory() {
		return activeTopicHistory;
	}

	public void setActiveTopicHistory(List<Integer> activeTopicHistory) {
		this.activeTopicHistory = activeTopicHistory;
	}

	public List<Integer> getActiveTopicInDataHistory() {
		return activeTopicInDataHistory;
	}

	public void setActiveTopicInDataHistory(List<Integer> activeInDataTopicHistory) {
		this.activeTopicInDataHistory = activeInDataTopicHistory;
	}
}