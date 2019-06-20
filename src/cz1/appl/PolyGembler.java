package cz1.appl;

import org.apache.log4j.Logger;

import cz1.hmm.tools.DataPreparation;
import cz1.hmm.tools.Gembler;
import cz1.hmm.tools.Haplotyper;
import cz1.hmm.tools.LinkageMapConstructor;
import cz1.hmm.tools.RFEstimator;
import cz1.hmm.tools.TwoPointConstructor;
import cz1.simulation.tools.GBSSimulator;
import cz1.simulation.tools.PopulationSimulator;

public class PolyGembler {
	
	protected final static Logger myLogger = 
			Logger.getLogger(PolyGembler.class);
	
	public static void main(String[] args) {
	
		if(args.length<1) {
			printUsage();
			throw new RuntimeException("Undefined tool!!!");
		}
		String[] args2 = new String[args.length-1];
		System.arraycopy(args, 1, args2, 0, args2.length);
		switch(args[0].toLowerCase()) {
		case "popsimulation":
			PopulationSimulator popsimulator = new PopulationSimulator();
			popsimulator.setParameters(args2);
			popsimulator.run();
			break;
		case "gbssimulation":
			GBSSimulator gbssimulator = new GBSSimulator();
			gbssimulator.setParameters(args2);
			gbssimulator.run();
			break;
		/***
		case "gbspileup":
			GBSpileup gbspileup = new GBSpileup();
			gbspileup.setParameters(args2);
			gbspileup.run();
			break;
		**/
		case "datapreparation":
			DataPreparation datapreparation = new DataPreparation();
			datapreparation.setParameters(args2);
			datapreparation.run();
			break;
		case "haplotyper":
			Haplotyper haplotyper = new Haplotyper();
			haplotyper.setParameters(args2);
			haplotyper.run();
			break;
        case "rfestimator":
            RFEstimator rfEstimator = new RFEstimator();
            rfEstimator.setParameters(args2);
            rfEstimator.run();
            break;
        case "map":
            LinkageMapConstructor lgc = new LinkageMapConstructor();
            lgc.setParameters(args2);
            lgc.run();
            break;
        case "twopoint":
            TwoPointConstructor twopoint = new TwoPointConstructor();
            twopoint.setParameters(args2);
            twopoint.run();
            break;
        case "gembler":
			Gembler gembler = new Gembler();
			gembler.setParameters(args2);
			gembler.run();
			break;
		default:
			printUsage();
			throw new RuntimeException("Undefined tool!!!");
		}
	}
	
	private static void printUsage() {
		// TODO Auto-generated method stub
		myLogger.info(
				"\n\nUsage is as follows:\n"
						+ " popsimulation       Simulate a full-sib mapping population. \n"
						+ " gbssimulation       Simulate GBS data. \n"
						//+ " gbspileup           Variant calling from GBS data. \n"
						+ " datapreparation     Prepare data for haplotype phasing. \n"
						+ " haplotyper          Contig/scaffold haplotype construction from a mapping population.\n"
						+ " rfestimator         Recombination fraction estimation.\n"
						+ " twopoint            Make two-point superscaffolds.\n"
						+ " map                 Contruct linkage maps.\n"
						+ " gembler             Run PolyGembler pipeline to construct genetic linkage maps/pseudomolecules.\n\n");
	}
}
