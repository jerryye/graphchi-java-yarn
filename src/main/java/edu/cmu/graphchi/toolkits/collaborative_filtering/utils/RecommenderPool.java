package edu.cmu.graphchi.toolkits.collaborative_filtering.utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jackson.map.ObjectMapper;

import edu.cmu.graphchi.datablocks.BytesToValueConverter;
import edu.cmu.graphchi.datablocks.IntConverter;
import edu.cmu.graphchi.engine.GraphChiEngine;
import edu.cmu.graphchi.preprocessing.FastSharder;
import edu.cmu.graphchi.toolkits.collaborative_filtering.algorithms.RecommenderAlgorithm;
import edu.cmu.graphchi.toolkits.collaborative_filtering.algorithms.RecommenderFactory;


/**
 * An object of this class represents a list of recommenders to be run by
 * in a single AggregateRecommender program. The algorithms run will save
 * on cost of IO and will be run in a pipelined way. Thus, if there are total
 * of 10 recommenders that are present in the pool, but only 4 can be run at a time 
 * then the 4 will be initially run and once a slot becomes empty, other recommenders
 * might start running.
 * @author mayank
 */

public class RecommenderPool {
    
    private DataSetDescription datasetDesc;

	private List<RecommenderAlgorithm> allRecommenders;
	//Ids (indices in the allRecommender's list) which are pending.
	private Set<Integer> pendingRecommenders;
	//Ids (indices in the allRecommender's list) which are pending.
	private Set<Integer> activeRecommenders;

	//Total Memory available to this pool. This will generally be equal to total heap size.
	private int totalMemory;	
	//Maximum available memory available after subtraccting the GraphChiEngine overhead
	private int maxAvailableMemory;
	
    //Current memory used by active recommenders.
	private int currentMemoryUsed;
	
	private FastSharder sharder;
	private BytesToValueConverter<RatingEdge> edgeDataConvertor;
	private int numShards;
    private int memoryBudget;
	
    private boolean isMutable;
    
	public RecommenderPool(DataSetDescription  datasetDesc, List<RecommenderAlgorithm> recommenders,
	        int numShards, int totalMemory) {
	    this.datasetDesc = datasetDesc;
	    this.totalMemory = totalMemory;
	    
	    if(numShards > 0) {
            this.numShards = numShards;
        } else {
            //TODO: Compute the number of shards dynamically?
            this.numShards = 1;
        }
	    
	    this.allRecommenders = new ArrayList<RecommenderAlgorithm>();
	    if(recommenders != null) {
	        this.allRecommenders.addAll(recommenders);
        }
	    
	    //Currently all the recommenders implemented do not mutate the edges.
	    this.isMutable = false;
	    
	    //Initialize various things in the pool
		this.currentMemoryUsed = 0;
		
		this.pendingRecommenders = new HashSet<Integer>();
		this.activeRecommenders = new HashSet<Integer>();
		
		this.edgeDataConvertor = createEdgeDataConvertor();
		
		this.maxAvailableMemory = computeMaxAvailableMemory(totalMemory);
	}
	
    private BytesToValueConverter<RatingEdge> createEdgeDataConvertor() {
	    if(edgeDataConvertor == null) {
	        this.edgeDataConvertor = new RatingEdgeConvertor(this.datasetDesc.getNumRatingFeatures());
	    }
	    return this.edgeDataConvertor;
    }

	/**
	 * Based on dataset description, numShards and memory budget, compute the memory required by engine
	 * and the maxAvailableMemory for recommenders.
	 * Currently the number of shards is provided as command-line argument and memory budget is constant.
	 * In future, number of shards and memory budget can probably be automatically chosen based on requirements
	 * of the program
	 * @return
	 */
    private int computeMaxAvailableMemory(int heapMemory) {
        //TODO: Maybe this should be dynamically chosen based on number of recommenders to run.
	    this.memoryBudget = 128;
	    
	    //This logic should be in the GraphChi Engine.
	    long numEdges = this.datasetDesc.getNumRatings();
	    int numVertices = this.datasetDesc.getNumUsers() + this.datasetDesc.getNumItems();
	    int edgeSize = this.edgeDataConvertor.sizeOf();
	    int vertexSize = 0;    //All vertices currently do not store any data
	    
	    // The memory requirement by graphchi engine depends on number of shards, memoryBudget,
	    // size of graph (number and sizes of edges and vertices) 
	    
	    int estimateEngineMemoryUsage;
	    estimateEngineMemoryUsage = GraphChiEngine.getEstimatedMemoryUsage(numShards, memoryBudget, numVertices, 
	            vertexSize, numEdges, edgeSize);
	    int vertexDataCacheMemUsage = VertexDataCache.getEstimatedMemory(datasetDesc);
	    
	    return heapMemory - (estimateEngineMemoryUsage + vertexDataCacheMemUsage); 
	}
	
	/**
	 * This method allows adding new recommenders and adds them to the list of
	 * pending recommenders. Hence, the size of recommender pool might vary dynamically.
	 * @param rec: The recommender to be added to the recommender pool.
	 */
	public void addNewRecommender(RecommenderAlgorithm rec) {
		//Add the new recommeder to pending list
		this.pendingRecommenders.add(this.allRecommenders.size());
		
		
		this.allRecommenders.add(rec);
	}
	
	// Set all recommenders to pending and assign some recommenders (based on maxMemory available) as
	// active.
	public void resetPool() {
		for(int i = 0; i < this.allRecommenders.size(); i++) {
			this.pendingRecommenders.add(i);
		}
		this.currentMemoryUsed = 0;
		
		//Naive greedy approach to choose initial set of recommenders.
		for(int i = 0; i < this.allRecommenders.size(); i++) {
			RecommenderAlgorithm rec = this.allRecommenders.get(i);
			if(rec.getEstimatedMemoryUsage() + this.currentMemoryUsed < this.maxAvailableMemory) {
				this.activeRecommenders.add(i);
				this.pendingRecommenders.remove(new Integer(i));
				this.currentMemoryUsed += rec.getEstimatedMemoryUsage();
			}
		}
	}
	
	//return a copy of active recommenders to ensure that activeRecommeders is immutable.
	//Since the list is small, its ok to create a new list everytime?
	public Set<Integer> getActiveRecommenders(){
		return  new HashSet<Integer>(this.activeRecommenders);
	}
	
	//return a copy of pending recommenders to ensure that activeRecommeders is immutable
	//Since the list is small, its ok to create a new list everytime?
	public Set<Integer> getPendingRecommenders(){
		return new HashSet<Integer>(this.pendingRecommenders);
	}
	
	/**
	 * Gets the ith recommender from the list of recommenders in the pool 
	 * @param i: The index of the recommender in this pool
	 * @return : The ith recommender in the pool
	 */
	public RecommenderAlgorithm getRecommender(int i) {
		return this.allRecommenders.get(i);
	}
	
	public int getRecommenderPoolSize() {
		return this.allRecommenders.size();
	}
	
	/**
	 * Set all these recommenders as completed. Note that the list of recommender ids might
	 * be active or pending. This method will mark them as complete.
	 * @param ids: Ids of recommenders to be marked as completed. 
	 */
	public void setRecommedersAsCompleted(List<Integer> ids){
		for(int i : ids) {
			this.pendingRecommenders.remove(new Integer(i));
			boolean wasActive = this.activeRecommenders.remove(new Integer(i));
			if(wasActive) {
				this.currentMemoryUsed -= this.allRecommenders.get(i).getEstimatedMemoryUsage();
			}
		}
		
		List<Integer> newRec = new ArrayList<Integer>();
		for(int i : this.pendingRecommenders) {
			RecommenderAlgorithm rec = this.allRecommenders.get(i);
			if(currentMemoryUsed + rec.getEstimatedMemoryUsage() < this.maxAvailableMemory) {
				newRec.add(new Integer(i));
				this.activeRecommenders.add(i);
				this.currentMemoryUsed += rec.getEstimatedMemoryUsage();
			}
		}
		this.pendingRecommenders.removeAll(newRec);
		
	}
	
	/**
	 * Creates a json file which represents all the recommender algorithms to be run in this pool.
	 * This is needed to ship this pool of recommenders to some other host in case of Apache YARN.
	 * The new config file created will be used to instantiate the graphchi program on the other host
	 * and run the program.
	 * @return
	 */
	public void createParamJsonFile(String fileName) throws Exception {
		List<Map<String, String>> allParams = new ArrayList<Map<String,String>>();
		for(RecommenderAlgorithm rec : this.allRecommenders) {
			allParams.add(rec.getParams().getParamsMap());
		}
		ObjectMapper mapper = new ObjectMapper();
		mapper.writeValue(new File(fileName), allParams);
	}
	
	public FastSharder createSharder(String scratchDir) throws IOException {
        return new FastSharder<Integer, RatingEdge>(scratchDir, this.numShards, null, 
                new RatingEdgeProcessor(), 
            new IntConverter(), this.edgeDataConvertor);
	}
	
	public boolean isMutable() {
	    return this.isMutable;
	}
	
    public DataSetDescription getDatasetDesc() {
        return datasetDesc;
    }

    public List<RecommenderAlgorithm> getAllRecommenders() {
        return allRecommenders;
    }

    public FastSharder getSharder() {
        return sharder;
    }

    public BytesToValueConverter<RatingEdge> getEdgeDataConvertor() {
        return edgeDataConvertor;
    }
    
    public int getNumShards() {
        return numShards;
    }

    public int getMemoryBudget() {
        return memoryBudget;
    }
    
    public int getMaxAvailableMemory() {
        return maxAvailableMemory;
    }
    
    public int getTotalMemory() {
        return totalMemory;
    }
    
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((allRecommenders == null) ? 0 : allRecommenders.hashCode());
        result = prime * result
                + ((datasetDesc == null) ? 0 : datasetDesc.hashCode());
        result = prime * result + numShards;
        result = prime * result + totalMemory;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        RecommenderPool other = (RecommenderPool) obj;
        if (allRecommenders == null) {
            if (other.allRecommenders != null)
                return false;
        } else if (!allRecommenders.equals(other.allRecommenders))
            return false;
        if (datasetDesc == null) {
            if (other.datasetDesc != null)
                return false;
        } else if (!datasetDesc.equals(other.datasetDesc))
            return false;
        if (numShards != other.numShards)
            return false;
        if (totalMemory != other.totalMemory)
            return false;
        return true;
    }
	
	//For testing purpose only
	public static void main(String[] args) {
		ProblemSetup problemSetup = new ProblemSetup(args);
		
		try {
		
			DataSetDescription dataDesc = new DataSetDescription();
			dataDesc.loadFromJsonFile(problemSetup.dataMetadataFile);
			
			//TODO: Do something else for vertex data cache.
			List<RecommenderAlgorithm> recommenders = RecommenderFactory.buildRecommenders(dataDesc, 
					problemSetup.paramFile, null, problemSetup);
			
			/*int mem = GraphChiEngine.getEstimatedMemoryUsage(16, 128, 497959, 0, 99072112, 4);
            System.out.println("Expected Mem Usage " + mem);*/
			
			/*RecommenderScheduler sched = new RecommenderScheduler(null, recommenders);
			List<RecommenderPool> pools = sched.splitIntoRecPools();
			
			pools.get(0).createParamJsonFile("abc.json");*/
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
