package cz1.ngs.model;

import java.util.LinkedList;

import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedPseudograph;
import org.jgrapht.graph.GraphWalk;
import org.jgrapht.traverse.BreadthFirstIterator;

public final class BFSShortestPath<V, E> {
	
	public static void main(String[] args) {
		// to test this class
		final DirectedWeightedPseudograph<Integer, DefaultWeightedEdge> graph = 
				new DirectedWeightedPseudograph<Integer, DefaultWeightedEdge>(DefaultWeightedEdge.class);
		graph.addVertex(0);
		graph.addVertex(1);
		graph.addVertex(2);
		graph.addVertex(3);
		graph.addVertex(4);
		graph.addVertex(5);
		graph.setEdgeWeight(graph.addEdge(0, 1), 1.0);
		graph.setEdgeWeight(graph.addEdge(0, 2), 2.0);
		graph.setEdgeWeight(graph.addEdge(3, 0), 3.0);
		graph.setEdgeWeight(graph.addEdge(1, 3), 4.0);
		graph.setEdgeWeight(graph.addEdge(4, 5), 5.0);
		
		BFSShortestPath<Integer, DefaultWeightedEdge> bfs = new BFSShortestPath<Integer, DefaultWeightedEdge>(graph);
		GraphPath<Integer, DefaultWeightedEdge> path = bfs.getPath(0, 3);
		
		if(path==null) {
			System.out.println("No path find!!!");
		} else {
			for(DefaultWeightedEdge edge : path.getEdgeList()) {
				System.out.println(edge.toString());
			}
		}
	}
	
    /**
     * Error message for reporting that a source vertex is missing.
     */
    private final static String GRAPH_MUST_CONTAIN_THE_SOURCE_VERTEX = 
    		"Graph must contain the source vertex!";
    /**
     * Error message for reporting that a sink vertex is missing.
     */
    private final static String GRAPH_MUST_CONTAIN_THE_SINK_VERTEX = 
    		"Graph must contain the sink vertex!";
	
	private final Graph<V, E> graph;
	
	public BFSShortestPath(Graph<V, E> graph) {
		this.graph = graph;
	}
	
    public GraphPath<V, E> getPath(V source, V sink){
    	if (!graph.containsVertex(source)) {
            throw new IllegalArgumentException(GRAPH_MUST_CONTAIN_THE_SOURCE_VERTEX);
        }
        if (!graph.containsVertex(sink)) {
            throw new IllegalArgumentException(GRAPH_MUST_CONTAIN_THE_SINK_VERTEX);
        }
        if (source.equals(sink)) {
            return createEmptyPath(source, sink);
        }
    	
        BreadthFirstIterator1<V, E> iter = new BreadthFirstIterator1<V, E>(graph, source);
        
        while(iter.hasNext()) {
            V vertex = iter.next();

            if (vertex.equals(sink)) {
                return createPath(iter, source, sink);
            }
        }

        return null;
    }

	private GraphPath<V, E> createPath(BreadthFirstIterator1<V, E> iter, V source, V sink) {
		LinkedList<E> edgeList = new LinkedList<>();

		V walk = sink;
		double weight = 0d;
        while (true) {
            E edge = iter.getSpanningTreeEdge(walk);
            if (edge == null) break;
            edgeList.addFirst(edge);
            walk = Graphs.getOppositeVertex(graph, edge, walk);
            weight += graph.getEdgeWeight(edge);
        }

        return new GraphWalk<>(graph, source, sink, null, edgeList, weight);
    }

    private static class BreadthFirstIterator1<V, E> extends BreadthFirstIterator<V, E> {

        public BreadthFirstIterator1(Graph<V,E> graph, V source) {
            super(graph, source);
            // default is false
            // iter.setCrossComponentTraversal(false);
        }

        @Override
        protected void encounterVertex(V vertex, E edge) {
            super.encounterVertex(vertex, edge);
            putSeenData(vertex, edge);
        }

        @SuppressWarnings("unchecked")
		public E getSpanningTreeEdge(V vertex) {
            return (E) getSeenData(vertex);
        }
    }
    
    /**
     * Create an empty path. Returns null if the source vertex is different than the target vertex.
     * 
     * @param source the source vertex
     * @param sink the sink vertex
     * @return an empty path or null if the source vertex is different than the target vertex
     */
    private final GraphPath<V, E> createEmptyPath(V source, V sink) {
        if (source.equals(sink)) {
            return GraphWalk.singletonWalk(graph, source, 0d);
        } else {
            return null;
        }
    }
}
