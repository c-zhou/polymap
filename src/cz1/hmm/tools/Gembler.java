package cz1.hmm.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import cz1.hmm.data.DataCollection;
import cz1.math.Algebra;
import cz1.util.ArgsEngine;
import cz1.util.Constants;
import cz1.util.Executor;
import cz1.util.Utils;
import cz1.util.Constants.Field;

public class Gembler extends Executor {

	private String in_vcf = null;
	private String assembly_file = null;
	private String out_prefix = null;
	private int max_iter = 100;
	private Field field = Field.PL;
	
	private int min_snpc = 5;
	private int min_depth = 0;
	private int max_depth = Integer.MAX_VALUE;
	private int min_qual = 0;
	private double min_maf = 0.1;
	private double max_missing = 0.5;
	private String[] parents;
	private int ploidy;
	
	private int[] repeat = new int[]{30,30,10};
	private int refine_round = 10;
	
	private int drop = 1;
	private double phi = 2;
	private int nB = 10;
	
	private double genome_size = -1;
	private double frac_thresh = .8;
	
	private String RLibPath = null;
	
	public Gembler() {
		this.require("Rscript");
	}
	
	@Override
	public void printUsage() {
		// TODO Auto-generated method stub
		myLogger.info(
				"\n\nUsage is as follows:\n"
						+ " Common:\n"
						+ "     -i/--input-vcf              Input VCF file.\n"
						+ "     -o/--prefix                 Output file location, create the directory if not exist.\n"
						+ "     -p/--ploidy                 Ploidy of genome (default 2).\n"
						+ "     -S/--random-seed            Random seed for this run.\n"
						+ "     -t/--threads                Threads (default 1).\n"
						+ "     -rlib/--R-external-libs     External library paths that you want R to search for packages.\n"
						+ "                                 This could be useful if you are not root users and install R \n"
						+ "                                 packages in directories other than default. \n"
						+ "                                 Multiple paths separated by ':' could be provided.\n"
						+ "\n"
						+ " Data preparation:\n"
						+ "     -l/--min-depth              Minimum depth to keep a SNP (DP).\n"
						+ "     -u/--max-depth              Maximum depth to keep a SNP (DP).\n"
						+ "     -q/--min-qual               Minimum quality to keep a SNP (QUAL).\n"
						+ "     -mf/--min-maf               Minimum minor allele frequency to keep a SNP (default 0.1).\n"
						+ "     -mm/--max-missing           Maximum proportion of missing data to keep a SNP (default 0.5).\n"
						+ "\n"
						+ " Haplotype inferring:\n"
						+ "     -x/--max-iter               Maxmium rounds for EM optimization (default 100).\n"
						+ "     -f/--parent                 Parent samples (separated by a \":\").\n"
						+ "     -G/--genotype               Use genotypes to infer haplotypes. Mutually exclusive with \n"
						+ "                                 option -D/--allele-depth and -L/--genetype likelihood.\n"
						+ "     -D/--allele-depth           Use allele depth to infer haplotypes. Mutually exclusive \n"
						+ "                                 with option -G/--genotype and -L/--genetype likelihood.\n"
						+ "     -L/--genotype-likelihood    Use genotype likelihoods to infer haplotypes. Mutually \n"
						+ "                                 exclusive with option -G/--genotype and -L/--allele-depth \n"
						+ "                                 (default).\n"
						+ "     -c/--min-snp-count          Minimum number of SNPs on a scaffold to run.\n"
						+ "     -r/--repeat                 Repeat haplotype inferring for multiple times as EM algorithm \n"
						+ "                                 could be trapped in local optima. The program takes three values \n"
						+ "                                 from here, i.e., for scaffold, for superscaffold and for genetic \n"
						+ "                                 linkage map refinement. Three values should be separated by ',' \n"
						+ "                                 (default 30,30,10).\n"
						+ "     -rr/--refinement-round      Number of rounds to refine pseudomelecules (default 10.)\n"
						+ "\n"
						+ " Recombination frequency estimation:\n"
						+ "     -nb/--best                  The most likely nb haplotypes will be used (default 10).\n"
						+ "     -phi/--skew-phi             For a haplotype inference, the frequencies of parental \n"
						+ "                                 haplotypes need to be in the interval [1/phi, phi], \n"
						+ "                                 otherwise will be discarded (default 2).\n"
						+ "     -nd/--drop                  At least nd haplotype inferences are required for calculation.\n"
						+ " Pseudomolecule construction:\n"
						+ "     -a/--input-assembly         Input assembly fasta file.\n"
						+ "     -frac/--frac-thresold       Lower threshold the genetic linkage map covers to construct \n"
						+ "                                 pseudomolecules (default 0.8).\n"
						+ "     -gz/--genome-size           The estimated genome size (default size of the reference assembly). \n"
						+"\n"
				);
	}

	@Override
	public void setParameters(String[] args) {
		// TODO Auto-generated method stub
		// create the command line parser

		if (args.length == 0) {
			printUsage();
			throw new IllegalArgumentException("\n\nPlease use the above arguments/options.\n\n");
		}

		if (myArgsEngine == null) {
			myArgsEngine = new ArgsEngine();
			myArgsEngine.add("-i", "--input-vcf", true);
			myArgsEngine.add("-a", "--input-assembly", true);
			myArgsEngine.add("-o", "--prefix", true);
			myArgsEngine.add("-x", "--max-iter", true);
			myArgsEngine.add("-p", "--ploidy", true);
			myArgsEngine.add("-f", "--parent", true);
			myArgsEngine.add("-G", "--genotype", false);
			myArgsEngine.add("-D", "--allele-depth", false);
			myArgsEngine.add("-L", "--genotype-likelihood", false);
			myArgsEngine.add("-S", "--random-seed", true);
			myArgsEngine.add("-t", "--threads", true);
			myArgsEngine.add("-l", "--min-depth", true);
			myArgsEngine.add("-u", "--max-depth", true);
			myArgsEngine.add("-q", "--min-qual", true);
			myArgsEngine.add("-mf", "--min-maf", true);
			myArgsEngine.add("-mm", "--max-missing", true);
			myArgsEngine.add("-c", "--min-snp-count", true);
			myArgsEngine.add("-r", "--repeat", true);
			myArgsEngine.add("-rr", "--refinement-round", true);
			myArgsEngine.add("-nb", "--best", true);
			myArgsEngine.add("-phi", "--skew-phi", true);
			myArgsEngine.add("-nd", "--drop", true);
			myArgsEngine.add("-rlib", "--R-external-libs", true);
			myArgsEngine.add("-frac", "--frac-thresold", true);
			myArgsEngine.add("-gz", "--genome-size", true);
			myArgsEngine.parse(args);
		}
		
		if(myArgsEngine.getBoolean("-i")) {
			in_vcf = myArgsEngine.getString("-i");
		} else {
			printUsage();
			throw new IllegalArgumentException("Please specify your input VCF file.");
		}
		
		if(myArgsEngine.getBoolean("-a")) {
			assembly_file = myArgsEngine.getString("-a");
		}

		if(myArgsEngine.getBoolean("-o")) {
			out_prefix = myArgsEngine.getString("-o");
		}  else {
			printUsage();
			throw new IllegalArgumentException("Please specify your output file prefix.");
		}
		
		if(myArgsEngine.getBoolean("-x")) {
			max_iter = Integer.parseInt(myArgsEngine.getString("-x"));
		}
		
		if(myArgsEngine.getBoolean("-p")) {
			this.ploidy = Integer.parseInt(myArgsEngine.getString("-p"));
		}
		
		if(myArgsEngine.getBoolean("-f")) {
			this.parents = myArgsEngine.getString("-f").split(":");
		} else {
			printUsage();
			throw new IllegalArgumentException("Please specify the parent samples (seperated by a \":\").");
		}
		
		int c = 0;
		if(myArgsEngine.getBoolean("-G")) {
			field = Field.GT;
			c++;
		}
		
		if(myArgsEngine.getBoolean("-D")) {
			field = Field.AD;
			c++;
		}
		
		if(myArgsEngine.getBoolean("-L")) {
			field = Field.PL;
			c++;
		}
		if(c>1) throw new IllegalArgumentException("Options -G/--genotype, "
				+ "-D/--allele-depth, and -L/--genotype-likelihood "
				+ "are exclusive!!!");
		
		if(myArgsEngine.getBoolean("-S")) {
			Constants.seed = Long.parseLong(myArgsEngine.getString("-S"));
			Constants.setRandomGenerator();
		}
		
		if(myArgsEngine.getBoolean("-t")) {
			THREADS = Integer.parseInt(myArgsEngine.getString("-t"));
		}
		
		if (myArgsEngine.getBoolean("-l")) {
			min_depth = Integer.parseInt(myArgsEngine.getString("-l"));
		}
		
		if (myArgsEngine.getBoolean("-u")) {
			max_depth = Integer.parseInt(myArgsEngine.getString("-u"));
		}
		
		if (myArgsEngine.getBoolean("-q")) {
			min_qual = Integer.parseInt(myArgsEngine.getString("-q"));
		}
		
		if (myArgsEngine.getBoolean("-mf")) {
			min_maf = Double.parseDouble(myArgsEngine.getString("-mf"));
		}
		
		if (myArgsEngine.getBoolean("-mm")) {
			max_missing = Double.parseDouble(myArgsEngine.getString("-mm"));
		}
		
		if (myArgsEngine.getBoolean("-c")) {
			min_snpc = Integer.parseInt(myArgsEngine.getString("-c"));
		}
		
		if (myArgsEngine.getBoolean("-r")) {
			String[] rs = myArgsEngine.getString("-r").split(",");
			if(rs.length!=3) 
				throw new IllegalArgumentException("Option -r/--repeat should "
						+ "be 3 integers seperated by \",\".");
			for(int i=0; i<3; i++)
				repeat[i] = Integer.parseInt(rs[i]);
		}
		
		if (myArgsEngine.getBoolean("-rr")) {
			refine_round = Integer.parseInt(myArgsEngine.getString("-rr"));
		}
		
		if (myArgsEngine.getBoolean("-nb")) {
			nB = Integer.parseInt(myArgsEngine.getString("-nb"));
		}
		
		if (myArgsEngine.getBoolean("-phi")) {
			phi = Double.parseDouble(myArgsEngine.getString("-phi"));
		}
		
		if (myArgsEngine.getBoolean("-nd")) {
			drop = Integer.parseInt(myArgsEngine.getString("-nd"));
		}
		
		if (myArgsEngine.getBoolean("-rlib")) {
			RLibPath = myArgsEngine.getString("-rlib");
		}
		
		if(myArgsEngine.getBoolean("-frac")) {
			frac_thresh = Double.parseDouble(myArgsEngine.getString("-frac"));
		}
		
		if(myArgsEngine.getBoolean("-gz")) {
			genome_size = Double.parseDouble(myArgsEngine.getString("-gz"));
		}
	}

	@Override
	public void run() {
		// TODO Auto-generated method stub
	
		Constants.throwRuntimeException("feature is under development!!!");
		
		Utils.makeOutputDir(new File(out_prefix));
		String prefix_vcf = new File(in_vcf).getName().
				replaceAll(".vcf.gz$", "").
				replaceAll(".vcf$", "");
		
		//#### STEP 01 filter SNPs and create ZIP file
		Utils.makeOutputDir(new File(out_prefix+"/data"));
		new DataPreparation(in_vcf,
				ploidy, 
				min_depth, 
				max_depth, 
				min_qual, 
				min_maf, 
				max_missing, 
				prefix_vcf+".recode",
				out_prefix+"/data/")
		.run();
		
		//#### STEP 02 single-point haplotype inferring
		final String out = out_prefix+"/single_hap_infer";
		String in_zip = out_prefix+"/data/"+prefix_vcf+".recode.zip";
		final Map<String, Integer> scaffs = DataCollection.readScaff(in_zip);
		final String expr_id = new File(in_zip).getName().
				replaceAll(".zip$", "").
				replace(".", "").
				replace("_", "");
		Utils.makeOutputDir(new File(out));
		this.runHaplotyper(scaffs, expr_id, in_zip, repeat[0], out);
		
		//#### STEP 03 assembly errors
		final String metafile_prefix = out_prefix+"/meta/";
		Utils.makeOutputDir(new File(metafile_prefix));
		final String ass_err_map = metafile_prefix+prefix_vcf;
		AssemblyError assemblyError = new AssemblyError(out, 
				ass_err_map,
				expr_id, 
				THREADS,
				phi,
				drop,
				nB);
		
		assemblyError.run();
		final String prefix_vcf_assError = prefix_vcf+".recode.assError";
		Set<String> scaff_breakage = 
				assemblyError.split(out_prefix+"/data/"+prefix_vcf+".recode.vcf",
						metafile_prefix+prefix_vcf_assError+".vcf");
		if(!scaff_breakage.isEmpty()) {
			this.move(assemblyError.errs().keySet(), out, expr_id);

			new ZipDataCollection(
					metafile_prefix+prefix_vcf_assError+".vcf",
					prefix_vcf_assError,
					metafile_prefix)
			.run();
			in_zip = metafile_prefix+prefix_vcf_assError+".zip";
			final Map<String, Integer> scaffs_assError = DataCollection.readScaff(
					in_zip, scaff_breakage);
			this.runHaplotyper(scaffs_assError, expr_id, in_zip, repeat[0], out);
		}
	
		//#### STEP 04 recombination frequency estimation
		final String rf_prefix = metafile_prefix+prefix_vcf;
		new SinglePointAnalysis (out, 
				rf_prefix,
				expr_id, 
				THREADS,
				phi,
				drop,
				nB).run();
		new TwoPointAnalysis (out, 
				rf_prefix,
				expr_id, 
				THREADS,
				phi,
				drop,
				nB).run();
		
		//#### STEP 05 building superscaffolds (nearest neighbour joining)
		final String temfile_prefix = metafile_prefix+".tmp/";
		Utils.makeOutputDir(new File(temfile_prefix));
		final String concorde_path = 
				RFUtils.makeExecutable("cz1/hmm/executable/concorde", temfile_prefix);
		final String nnssR_path = 
				RFUtils.makeExecutable("cz1/hmm/scripts/make_nnsuperscaffold.R", temfile_prefix);
		RFUtils.makeExecutable("cz1/hmm/scripts/include.R", temfile_prefix);
		new File(concorde_path).setExecutable(true, true);
		RFUtils.makeRMatrix(rf_prefix+".txt", rf_prefix+".RData");
		String command = "Rscript "+nnssR_path+" "
				+ "-i "+rf_prefix+".RData "
				+ "-n 2 "
				+ "-o "+rf_prefix+".nnss "
				+ "--concorde "+new File(concorde_path).getParent()
				+ (RLibPath==null ? "" : " --include "+RLibPath);
		this.consume(this.bash(command));
		
		//#### STEP 06 multi-point hapotype inferring
		final String mm_out = out_prefix+"/2nn_hap_infer";
		Utils.makeOutputDir(new File(mm_out));
		final Set<String> mm_scaffs = new HashSet<String>();
		final Map<String, String> mm_seperation = new HashMap<String, String>();
		final Map<String, String> mm_reverse = new HashMap<String, String>();
		this.readSS(rf_prefix+".nnss", mm_scaffs, mm_seperation, mm_reverse);
		this.runHaplotyper(mm_scaffs, mm_seperation, mm_reverse,
				expr_id, in_zip, repeat[1], mm_out);
		
		//#### STEP 07 recombination frequency estimation
		final String mm_rf_prefix = metafile_prefix+"2nn_"+prefix_vcf;
		new SinglePointAnalysis (mm_out, 
				mm_rf_prefix,
				expr_id, 
				THREADS,
				phi,
				drop,
				nB).run();
		new TwoPointAnalysis (mm_out, 
				mm_rf_prefix,
				expr_id, 
				THREADS,
				phi,
				drop,
				nB).run();
		
		//#### STEP 08 genetic mapping
		final String mklgR_path = 
				RFUtils.makeExecutable("cz1/hmm/scripts/make_geneticmap.R", temfile_prefix);
		RFUtils.makeRMatrix(mm_rf_prefix+".txt", mm_rf_prefix+".RData");
		command = "Rscript "+mklgR_path+" "
				+ "-i "+mm_rf_prefix+".RData "
				+ "-m "+mm_rf_prefix+".map "
				+ "-o "+mm_rf_prefix+" "
				+ "--concorde "+new File(concorde_path).getParent()
				+ (RLibPath==null ? "" : " --include "+RLibPath);
		this.consume(this.bash(command));
		
		//#### STEP 09 genetic map refinement
		final String out_refine = out_prefix+"/refine_hap_infer/";
		Utils.makeOutputDir(new File(out_refine));
		this.readSS(mm_rf_prefix+".par", mm_scaffs, mm_seperation, mm_reverse);
		
		final int lgN = mm_scaffs.size();
		for(int i=0; i<lgN; i++) {
			final String out_refine_i = out_refine+"lg"+StringUtils.leftPad(""+i, 2, '0')+"/";
			Utils.makeOutputDir(new File(out_refine_i));
			for(int j=0; j<this.refine_round; j++) {
				final String out_refine_ij = out_refine_i+j+"/";
				final String out_refine_ij_haps = out_refine_ij+"haplotypes/";
				Utils.makeOutputDir(new File(out_refine_ij));
				Utils.makeOutputDir(new File(out_refine_ij_haps));
			}
		}
		
		final List<String> scaff_list = new ArrayList<String>(mm_scaffs);
		Collections.sort(scaff_list, new Comparator<String>() {
			@Override
			public int compare(String scaff0, String scaff1) {
				// TODO Auto-generated method stub
				return StringUtils.countMatches(scaff1, ":")-
						StringUtils.countMatches(scaff0, ":");
			}
		});
		
		final int[][] task_table = new int[refine_round][lgN];
		final int[] task_progress = new int[lgN];
		final double[][] lgCM = new double[lgN][refine_round];
		
		for (int[] i : task_table)
		    Arrays.fill(i, repeat[2]);
		final String final_zip = in_zip;
		
		this.initial_thread_pool();
		
		for(int i=0; i<lgN; i++) { 
			final String scaff_i = scaff_list.get(i);
			final String out_refine_i = out_refine+"lg"+StringUtils.leftPad(""+i, 2, '0')+"/";
			final String out_refine_ij = out_refine_i+0+"/";
			final String out_refine_ij_haps = out_refine_ij+"haplotypes/";
			for(int j=0; j<repeat[2]; j++) {
				executor.submit(new Runnable(){
					private int i;
					private int j;
					@Override
					public void run() {
						// TODO Auto-generated method stub
						try {
							new Haplotyper(final_zip,
									out_refine_ij_haps,
									scaff_i,
									mm_seperation.get(scaff_i),
									mm_reverse.get(scaff_i),
									ploidy,
									field,
									expr_id,
									max_iter).run();
							synchronized (task_table) {
								task_table[i][j]--;
								task_table.notify();
							}
						} catch (Exception e) {
							Thread t = Thread.currentThread();
							t.getUncaughtExceptionHandler().uncaughtException(t, e);
							e.printStackTrace();
							executor.shutdown();
							System.exit(1);
						}
					}

					public Runnable init(final int i, final int j) {
						// TODO Auto-generated method stub
						this.i = i;
						this.j = j;
						return (this);
					}
				}.init(0, i));
			}
		}
		
		while(true) {
			for(int i=0; i<lgN; i++) {
				
				if(task_progress[i]<refine_round &&
						task_table[task_progress[i]][i]==0) {
					
					final String out_refine_i = out_refine+"lg"+StringUtils.leftPad(""+i, 2, '0')+"/";
					final String out_refine_ij = out_refine_i+task_progress[i]+"/";
					final String out_refine_ij_haps = out_refine_ij+"haplotypes/";
					final String mm_rf_prefix_ij = out_refine_ij+"1";
					
					new SinglePointAnalysis (out_refine_ij_haps, 
							mm_rf_prefix_ij,
							expr_id, 
							THREADS,
							Double.POSITIVE_INFINITY,
							drop,
							nB).run();
					new TwoPointAnalysis (out_refine_ij_haps, 
							mm_rf_prefix_ij,
							expr_id, 
							THREADS,
							Double.POSITIVE_INFINITY,
							drop,
							nB).run();
					RFUtils.makeRMatrix(mm_rf_prefix_ij+".txt", mm_rf_prefix_ij+".RData");
					command = "Rscript "+mklgR_path+" "
							+ "-i "+mm_rf_prefix_ij+".RData "
							+ "-m "+mm_rf_prefix_ij+".map "
							+ "-o "+mm_rf_prefix_ij+" "
							+ "-1 "
							+ "--concorde "+new File(concorde_path).getParent()
							+ (RLibPath==null ? "" : " --include "+RLibPath);
					this.consume(this.bash(command));
					lgCM[i][task_progress[i]] = this.lgCM(mm_rf_prefix_ij+".log");
					
					task_progress[i]++;
					
					if(task_progress[i]<refine_round) {
						
						final String out_refine_ijx_haps = out_refine_i+task_progress[i]+"/"+"haplotypes/";
						
						final Set<String> mm_scaffs_i = new HashSet<String>();
						final Map<String, String> mm_seperation_i = new HashMap<String, String>();
						final Map<String, String> mm_reverse_i = new HashMap<String, String>();
						this.readSS(mm_rf_prefix_ij+".par", 
								mm_scaffs_i, mm_seperation_i, mm_reverse_i);
						final String scaff_i = mm_scaffs_i.iterator().next();

						for(int j=0; j<repeat[2]; j++) {
							executor.submit(new Runnable(){
								private int i;
								private int j;
								@Override
								public void run() {
									// TODO Auto-generated method stub
									try {
										new Haplotyper(final_zip,
												out_refine_ijx_haps,
												scaff_i,
												mm_seperation_i.get(scaff_i),
												mm_reverse_i.get(scaff_i),
												ploidy,
												field,
												expr_id,
												max_iter).run();
										synchronized (task_table) {
											task_table[i][j]--;
											task_table.notify();
										}
									} catch (Exception e) {
										Thread t = Thread.currentThread();
										t.getUncaughtExceptionHandler().uncaughtException(t, e);
										e.printStackTrace();
										executor.shutdown();
										System.exit(1);
									}
								}

								public Runnable init(final int i, final int j) {
									// TODO Auto-generated method stub
									this.i = i;
									this.j = j;
									return (this);
								}
							}.init(task_progress[i], i));
						}
					}
				}
			}
			boolean done = true;
			for(int i=0; i<lgN; i++)
				if(task_progress[i]<refine_round)
					done = false;
			if(done) break;
		}
		this.waitFor();
		
		final String[] selected = new String[lgN];
		for(int i=0; i<lgN; i++)
			selected[i] = out_refine+"lg"+StringUtils.leftPad(""+i, 2, '0')+
				"/"+Algebra.minIndex(lgCM[i])+"/";
		this.makeSelectedLG(selected, metafile_prefix);
		
		//#### STEP 10 pseudo molecules construction
		if(assembly_file==null) myLogger.info("No assembly file provided, "
				+ "pseudomolecule construction module skipped.");
		new Pseudomolecule(
				metafile_prefix+"genetic_linkage_map.mct",
				this.assembly_file,
				metafile_prefix+"pseudomolecules.fa",
				assemblyError.errs()).run();
		
		//#### STEP 11 result files
		final String results_dir = out_prefix+"/results";
		Utils.makeOutputDir(new File(results_dir));
		try {
			Files.move(Paths.get(metafile_prefix+"/genetic_linkage_map.mct"), 
					Paths.get(results_dir+"/genetic_linkage_map.mct"),
					StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			Files.move(Paths.get(metafile_prefix+"/genetic_linkage_map.log"), 
					Paths.get(results_dir+"/genetic_linkage_map.log"),
					StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
			Files.move(Paths.get(metafile_prefix+"/pseudomolecules.fa"), 
					Paths.get(results_dir+"/pseudomolecules.fa"),
					StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void makeSelectedLG(String[] selected, String out) {
		// TODO Auto-generated method stub
		try {
			BufferedWriter bw_log = Utils.getBufferedWriter(out+"genetic_linkage_map.log");
			BufferedReader[] br_logs = new BufferedReader[selected.length];
			for(int i=0; i<br_logs.length; i++) 
				br_logs[i] = Utils.getBufferedReader(selected[i]+"1.log");
			String line = null;
			for(int i=0; i<br_logs.length; i++) 
				while( (line=br_logs[i].readLine())!=null &&
				!line.startsWith("$") ) {}
			while( line!=null ) {
				if(line.startsWith("$")) {
					bw_log.write(line+"\n");
					for(int i=0; i<br_logs.length; i++) 
						while( (line=br_logs[i].readLine())!=null &&
						!line.startsWith("$") )
							if(line.length()>0) bw_log.write(line+"\n");
					bw_log.write("\n");
				}
			}
			for(int i=0; i<br_logs.length; i++) br_logs[i].close();
			bw_log.close();
			
			BufferedWriter bw_mct = Utils.getBufferedWriter(out+"genetic_linkage_map.mct");
			for(int i=0; i<selected.length; i++) { 
				BufferedReader br_mct = Utils.getBufferedReader(selected[i]+"1.mct");
				br_mct.readLine();
				bw_mct.write("group\tLG"+StringUtils.leftPad(""+i, 2, '0')+"\n");
				while( (line=br_mct.readLine())!=null )
					if(line.length()>0) bw_mct.write(line+"\n");
				bw_mct.write("\n");
				br_mct.close();
			}
			bw_mct.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private double lgCM(String in_file) {
		// TODO Auto-generated method stub
		try {
			BufferedReader br = Utils.getBufferedReader(in_file);
			String line;
			while( (line=br.readLine())!=null &&
					!line.startsWith("$cm") ){}
			line = br.readLine();
			String[] s = line.split(",");
			br.close();
			double cm = 0.0;
			for(int i=0; i<s.length; i++)
				cm += Double.parseDouble(s[i]);
			return cm;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return -1;
	}

	private void readSS(String ss_in, 
			Set<String> mm_scaffs,
			Map<String, String> mm_seperation,
			Map<String, String> mm_reverse) {
		// TODO Auto-generated method stub
		mm_scaffs.clear();
		mm_seperation.clear();
		mm_reverse.clear();
		try {
			BufferedReader mm_br = Utils.getBufferedReader(ss_in);
			String line;
			String[] s;
			while( (line=mm_br.readLine())!=null ) {
				if(!line.startsWith("-c")) continue;
				s = line.split("\\s+");
				mm_scaffs.add(s[1]);
				if(s.length>2) {
					mm_seperation.put(s[1], s[3]);
					mm_reverse.put(s[1], s[5]);
				} else {
					mm_seperation.put(s[1], "0");
					mm_reverse.put(s[1], "false");
				}
			}
			mm_br.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void move(final Set<String> scaff_breakage, 
			final String out, 
			final String expr_id) {
		// TODO Auto-generated method stub
		String out_err = out+"/assembly_error";
		Utils.makeOutputDir(new File(out_err));
		for(final String scaff : scaff_breakage) {
			File[] files = new File(out).listFiles(
					new FilenameFilter() {
						@Override
						public boolean accept(File dir, String name) {
							return name.matches("^"+expr_id+"\\."+scaff+"\\..*");    
						}
					});
			for(File f : files)
				try {
					Files.move(f.toPath(), 
							Paths.get(out_err+"/"+f.getName()),
							StandardCopyOption.REPLACE_EXISTING,
							StandardCopyOption.ATOMIC_MOVE);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		}
	}

	private void runHaplotyper(final Map<String, Integer> scaffs,
			final String expr_id,
			final String in_zip,
			final int repeat,
			final String out) {
		// TODO Auto-generated method stub
		this.initial_thread_pool();
		for(final String scaff : scaffs.keySet()) {
			
			if(scaffs.get(scaff)<this.min_snpc) continue;
			
			for(int i=0; i<repeat; i++) {
				executor.submit(new Runnable(){

					@Override
					public void run() {
						// TODO Auto-generated method stub
						try {
							new Haplotyper(in_zip,
									out,
									new String[]{scaff},
									ploidy,
									field,
									expr_id,
									max_iter).run();
						} catch (Exception e) {
							Thread t = Thread.currentThread();
							t.getUncaughtExceptionHandler().uncaughtException(t, e);
							e.printStackTrace();
							executor.shutdown();
							System.exit(1);
						}
					}
				});
			}
		}
		this.waitFor();
	}
	
	private void runHaplotyper(final Set<String> scaffs,
			final Map<String, String> seperation,
			final Map<String, String> reverse,
			final String expr_id,
			final String in_zip,
			final int repeat,
			final String out) {
		// TODO Auto-generated method stub
		this.initial_thread_pool();
		for(final String scaff : scaffs) {
			for(int i=0; i<repeat; i++) {
				executor.submit(new Runnable(){

					@Override
					public void run() {
						// TODO Auto-generated method stub
						try {
							new Haplotyper(in_zip,
									out,
									scaff,
									seperation.get(scaff),
									reverse.get(scaff),
									ploidy,
									field,
									expr_id,
									max_iter).run();
						} catch (Exception e) {
							Thread t = Thread.currentThread();
							t.getUncaughtExceptionHandler().uncaughtException(t, e);
							e.printStackTrace();
							executor.shutdown();
							System.exit(1);
						}
					}
				});
			}
		}
		this.waitFor();
	}
}
