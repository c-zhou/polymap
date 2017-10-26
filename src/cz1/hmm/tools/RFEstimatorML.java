package cz1.hmm.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.text.NumberFormat;
import java.text.DecimalFormat;
import java.lang.StringBuilder;

import cz1.hmm.tools.RFUtils.FileExtraction;
import cz1.hmm.tools.RFUtils.FileLoader;
import cz1.hmm.tools.RFUtils.FileObject;
import cz1.hmm.tools.RFUtils.InputStreamObj;
import cz1.hmm.tools.RFUtils.PhasedDataCollection;
import cz1.util.Algebra;
import cz1.util.ArgsEngine;
import cz1.util.Combination;
import cz1.util.Constants;
import cz1.util.Utils;
import cz1.util.JohnsonTrotter;
import cz1.util.Permutation;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.math3.exception.MathIllegalArgumentException;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.inference.ChiSquareTest;
import org.apache.commons.math3.stat.inference.GTest;

// recombination frequency estimator from most likely paths
public class RFEstimatorML extends RFUtils {	

	// private static long[] scale_times = new long[10]; 

	@Override
	public void printUsage() {
		// TODO Auto-generated method stub
		myLogger.info(
				"\n\nUsage is as follows:\n"
						+ " -i/--hap-file               Directory with input haplotype files.\n"
						+ " -o/--prefix                 Output file prefix.\n"
						+ " -ex/--experiment-id         Common prefix of haplotype files for this experiment.\n"
						+ " -f/--parent                 Parent samples (seperated by a \":\").\n"
						+ " -p/--ploidy                 Ploidy of genome (default 2).\n"
						+ " -nb/--best                  The most likely nb haplotypes will be used (default 10).\n"
						+ " -phi/--skew-phi             For a haplotype inference, the frequencies of parental \n"
						+ "                             haplotypes need to be in the interval [1/phi, phi], \n"
						+ "                             otherwise will be discared (default 2).\n"
						+ " -nd/--drop                  At least nd haplotype inferences are required for \n"
						+ "                             a contig/scaffold to be analysed (default 1).\n"
						+ " -t/--threads                Threads (default 1).\n"	
				);
	}

	@Override
	public void setParameters(String[] args) {
		// TODO Auto-generated method stub
		if (args.length == 0) {
			printUsage();
			throw new IllegalArgumentException("\n\nPlease use the above arguments/options.\n\n");
		}

		if (myArgsEngine == null) {
			myArgsEngine = new ArgsEngine();
			myArgsEngine.add( "-ex", "--experiment-id", true);
			myArgsEngine.add( "-i", "--hap-file", true);
			myArgsEngine.add( "-o", "--prefix", true);
			myArgsEngine.add( "-f", "--parent", true);
			myArgsEngine.add( "-p", "--ploidy", true);
			myArgsEngine.add( "-nb", "--best", true);
			myArgsEngine.add( "-t", "--threads", true);
			myArgsEngine.add( "-phi", "--skew-phi", true);
			myArgsEngine.add( "-nd", "--drop", true);
			myArgsEngine.parse(args);
		}
		
		if(myArgsEngine.getBoolean("-i")) {
			in_haps = myArgsEngine.getString("-i");
		} else {
			printUsage();
			throw new IllegalArgumentException("Please specify your input zip file.");
		}

		if(myArgsEngine.getBoolean("-o")) {
			out_prefix = myArgsEngine.getString("-o");
		}  else {
			printUsage();
			throw new IllegalArgumentException("Please specify your output file prefix.");
		}
		
		if(myArgsEngine.getBoolean("-ex")) {
			expr_id = myArgsEngine.getString("-ex");
		}  else {
			expr_id = guessExperimentId();
			myLogger.warn("No experiment prefix provided, I guess it's "+expr_id+". Please\n"
					+ "specify it with -ex/--experiment-id option if it's incorrect.");
		}

		if(myArgsEngine.getBoolean("-f")) {
			founder_haps = myArgsEngine.getString("-f").split(":");
		} else {
			printUsage();
			throw new IllegalArgumentException("Please specify the parent samples (seperated by a \":\").");
		}
		
		if(myArgsEngine.getBoolean("-p")) {
			int ploidy = Integer.parseInt(myArgsEngine.getString("-p"));
			Constants.ploidy(ploidy);
			Constants._haplotype_z = ploidy*2;
			probs_uniform = new double[ploidy*2];
			Arrays.fill(probs_uniform, .5/ploidy);
			ploidy2 = ploidy/2;
			shift_bits2 = mask_length*ploidy;
		}
		
		if(myArgsEngine.getBoolean("-nb")) {
			best_n = Integer.parseInt(myArgsEngine.getString("-nb"));
		}
		
		if(myArgsEngine.getBoolean("-t")) {
			THREADS = Integer.parseInt(myArgsEngine.getString("-t"));
		}
		
		if(myArgsEngine.getBoolean("-phi")) {
			skew_phi = Integer.parseInt(myArgsEngine.getString("-phi"));
		}
		
		if(myArgsEngine.getBoolean("-nd")) {
			drop_thres = Integer.parseInt(myArgsEngine.getString("-nd"));
		}
		
		setHaps();
	}

	private BufferedWriter rfMinimumWriter;
	
	@Override
	public void run() {
		// TODO Auto-generated method stub

		this.initialise();
		
		this.hashDC();
		
		this.initial_thread_pool();
		rfMinimumWriter = Utils.getBufferedWriter(out_prefix+".txt");
		
		Set<String> kept_scaffs = new HashSet<String>();
		for(int i=0; i<this.dc.length; i++) {
			if(this.dc[i][0]!=null)
				kept_scaffs.add(this.dc[i][0].markers[0].
						replaceAll("_[0-9]{1,}$", ""));
		}
		try {
			for(String scaff : kept_scaffs)
				rfMinimumWriter.write("##"+scaff+"\n");
			for(Map.Entry<String, double[]> entry : this.conjPairRFs.entrySet()) {
				String key = entry.getKey();
				String[] s = key.split(Constants.scaff_collapsed_str);
				double[] rf = entry.getValue();
				String w = StatUtils.min(rf)+"\t";
				for(int k=0; k<rf.length; k++) w += rf[k]+"\t";
				w += s[0]+"\t"+s[1]+"\n";
				rfMinimumWriter.write(w);
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		long start = System.nanoTime();
		for(int i=0; i<dc.length; i++) 
			for(int j=i+1; j<dc.length; j++) 
				executor.submit(new MyRfCalculator(i, j));
		this.waitFor();
		
		long end = System.nanoTime();
		myLogger.info("elapsed, "+(end-start)+"; R pool key-value pair, "+rf_pool.size());
		try {
			rfMinimumWriter.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		this.initial_thread_pool();
		for(int i=0; i<dc.length; i++) 
			executor.submit(new MyMapCalculator(i));
		this.waitFor();
		writeMap();
		
		myLogger.info("["+Utils.getSystemTime()+"] DONE.");
	}
	
	protected MyPhasedDataCollection[][] dc;
	
	protected class MyPhasedDataCollection extends PhasedDataCollection {
		
		protected MyPhasedDataCollection(String file,
				String[] markers,
				int[] start_end) {
			// TODO Auto-generated constructor stub
			super(file, markers, start_end);
		}
		
		protected boolean[][][][] data;
		protected int[][][] hashcode;
		
		protected MyPhasedDataCollection(String file,
				String[] markers, 
				int[] start_end,
				boolean[][][][] data) {
			// TODO Auto-generated constructor stub
			super(file, markers, start_end);
			this.data = data;
		}
		
		protected void hash() {
			// TODO Auto-generated method stub
			this.hashcode = new int[2][2][nF1];
			for(int i=0; i<2; i++) {
				for(int j=0; j<2; j++) {
					boolean[][] d = this.data[i][j];
					int[] code = this.hashcode[i][j];
					for(int k=0; k<nF1; k++) {
						int key = 0;
						for(int p=0; p<Constants._ploidy_H; p++)
							key = (key<<mask_length)+(d[p][k] ? 1 : 0);
						code[k] = key;
					}
				}
			}
		}
		
		protected void data() {
			// TODO Auto-generated method stub
			this.data = this.data(
					this.start_end_position[0],
					this.start_end_position[1]);
		}

		protected MyPhasedDataCollection clone() {
			final boolean[][][][] data = 
					new boolean[2][2][Constants._ploidy_H][nF1];
			for(int i=0; i<2; i++) 
				for(int j=0; j<2; j++)
					for(int k=0; k<Constants._ploidy_H; k++)
						data[i][j][k] = this.data[i][j][k].clone();
			return new MyPhasedDataCollection(this.file, 
					this.markers, 
					this.start_end_position,
					data);
		}
		
		protected boolean[][][][] cloneData() {
			final boolean[][][][] data = 
					new boolean[2][2][Constants._ploidy_H][nF1];
			for(int i=0; i<2; i++) 
				for(int j=0; j<2; j++)
					for(int k=0; k<Constants._ploidy_H; k++)
						data[i][j][k] = this.data[i][j][k].clone();
			return data;
		}

		private boolean[][][][] data(int start, int end) {
			// TODO Auto-generated method stub
			boolean[][][][] data = new boolean[2][2][Constants._ploidy_H][nF1];

			try {
				InputStreamObj isObj = new InputStreamObj(this.file);
				isObj.getInputStream("PHASEDSTATES");
				BufferedReader br = Utils.getBufferedReader(isObj.is);
				
				String line;
				String[] s;
				String stateStr;
				int n=0;

				while( (line=br.readLine())!=null ) {

					if(!line.startsWith("#")) continue;
					s = line.split("\\s+|:");
					if(Arrays.asList(founder_haps).contains(s[2])) continue;

					stateStr = s[s.length-1];
					data[0][0][hap_index[stateStr.charAt(start)]][n] = true;
					data[0][1][hap_index[stateStr.charAt(end)]][n] = true;

					for(byte i=1; i<Constants._ploidy_H/2; i++) {
						line = br.readLine();
						s = line.split("\\s+");
						stateStr = s[s.length-1];
						data[0][0][hap_index[stateStr.charAt(start)]][n] = true;
						data[0][1][hap_index[stateStr.charAt(end)]][n] = true;
					}
					for(byte i=0; i<Constants._ploidy_H/2; i++) {
						line = br.readLine();
						s = line.split("\\s+");
						stateStr = s[s.length-1];
						data[1][0][hap_index[stateStr.charAt(start)]][n] = true;
						data[1][1][hap_index[stateStr.charAt(end)]][n] = true;
					}

					n++;
				}
				br.close();
				isObj.close();
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(1);
			}

			return data;
		}
	}
	
	private void hashDC() {
		// TODO Auto-generated method stub
		
		this.initial_thread_pool();
		for(int i=0; i<dc.length; i++) {
			for(int j=0; j<best_n; j++) {
				
				executor.submit(new Runnable() {
					private int i;
					private int j;
					
					@Override
					public void run() {
						// TODO Auto-generated method stub
						try {
							if(dc[i][j]==null) return;
							dc[i][j].data();
							dc[i][j].hash();
						} catch (Exception e) {
							Thread t = Thread.currentThread();
							t.getUncaughtExceptionHandler().uncaughtException(t, e);
							e.printStackTrace();
							executor.shutdown();
							System.exit(1);
						}
					}

					public Runnable init(int i, int j) {
						// TODO Auto-generated method stub
						this.i = i;
						this.j = j;
						return(this);
					}
					
				}.init(i, j));
			}
		}
		this.waitFor();
	}

	private final int[] hap_index = new int[256];
	private final int mask_length = 1;
	private int ploidy2 = 1;
	private int shift_bits2 = mask_length*2;
	private final Set<String> scaff_only = new HashSet<String>();	

	public RFEstimatorML() {}
			
	public RFEstimatorML (String in_haps, 
				String out_prefix,
				String expr_id, 
				int ploidy, 
				String[] founder_haps,
				int threads,
				double skew_phi,
				int drop_thres,
				int best_n) { 
		this.in_haps = in_haps;
		this.out_prefix = out_prefix;
		this.expr_id = expr_id;
		Constants.ploidy(ploidy);
		ploidy2 = ploidy/2;
		shift_bits2 = mask_length*ploidy;
		this.founder_haps = founder_haps;
		THREADS = threads;
		this.skew_phi = skew_phi;
		this.drop_thres = drop_thres;
		this.best_n = best_n;
		probs_uniform = new double[ploidy*2];
		Arrays.fill(probs_uniform, .5/ploidy);
		setHaps();
	}
	
	private char[][] haps = null;
	private int factorial_ploidy = -1;
	private int[][] johnson_trotter_permutation;
	
	private void setHaps() {
		factorial_ploidy = (int) Permutation.factorial(Constants._ploidy_H);
		List<List<Character>> haps_list = new ArrayList<List<Character>>();
		List<Character> hap = new ArrayList<Character>();
		for(int i=1; i<=Constants._ploidy_H; i++) {
			hap.add( i<10 ? Character.toChars('0'+i)[0] : 
				Character.toChars('a'+i-10)[0] );
		}
		haps_list.add(hap);
		hap = new ArrayList<Character>();
		for(int i=Constants._ploidy_H+1; 
				i<=Constants._haplotype_z; i++) {
			hap.add( i<10 ? Character.toChars('0'+i)[0] : 
				Character.toChars('a'+i-10)[0] );
		}
		haps_list.add(hap);
		haps = new char[haps_list.size()][haps_list.get(0).size()];
		for(int i=0; i<haps.length; i++) {
			for(int j=0; j<haps[i].length; j++) {
				haps[i][j] = haps_list.get(i).get(j);
				hap_index[haps[i][j]] = j;
			}
		}
		johnson_trotter_permutation = JohnsonTrotter.perm(Constants._ploidy_H);
		initialiseRFactory();
	}
	

	private void initialiseRFactory() {
		// TODO Auto-generated method stub
		List<List<Integer>> combs = Combination.combination(
				Constants._ploidy_H, ploidy2);
		for(int c=0; c<combs.size(); c++) {
			List<Integer> comb = combs.get(c);
			boolean[] bools = new boolean[Constants._ploidy_H];
			for(int i=0; i<ploidy2; i++)
				bools[comb.get(i)] = true;
			int key = hashcode(bools);
			for(int c2=0; c2<combs.size(); c2++) {
				List<Integer> comb2  = combs.get(c2);
				boolean[] bools2 = new boolean[Constants._ploidy_H];
				for(int i=0; i<ploidy2; i++)
					bools2[comb2.get(i)] = true;
				int key2 = hashcode(bools2);
				byte rf = recombinationFreq(bools, bools2);
				rf_factory.put((key<<shift_bits2)+key2, rf);
			}
		}
		return;
	}

	private byte recombinationFreq(boolean[] bools, 
			boolean[] bools2) {
		// TODO Auto-generated method stub
		byte rf = 0;
		for(int i=0; i<Constants._ploidy_H; i++)
			if(bools[i]&&bools2[i])
				rf++;
		return rf;
	}

	private int hashcode(boolean[] bools) {
		// TODO Auto-generated method stub
		int hash = 0;
		for(int i=0; i<Constants._ploidy_H; i++)
			hash = (hash<<mask_length)+(bools[i] ? 1 : 0);
		return hash;
	}

	protected class MyFileLoader extends FileLoader {

		public MyFileLoader(String id, FileObject[] files, int i) {
			// TODO Auto-generated constructor stub
			super(id, files, i);
		}

		@Override
		protected void collectData(int i, int z, FileObject fobj) {
			// TODO Auto-generated method stub
			dc[i][z] = new MyPhasedDataCollection(
					fobj.file,
					fobj.markers,
					fobj.start_end_position);
		}

		@Override
		protected String getPhaseFile() {
			// TODO Auto-generated method stub
			return "PHASEDSTATES";
		}		
	}
	
	protected class MyMapCalculator extends MapCalculator {
		
		public MyMapCalculator(int i) {
			// TODO Auto-generated constructor stub
			super(i);
		}

		@Override
		public void run() {
			// TODO Auto-generated method stub

			try {
				if(dc[i][0]==null) return;
				double[][] rfs = new double[dc[i].length][];

				for(int k=0; k<dc[this.i].length; k++) {
					MyPhasedDataCollection dc_ik = dc[i][k];
					if(dc_ik==null) break;
					myLogger.info(dc_ik.file);
					if(dc_ik !=null ) {
						rfs[k] = calcGDs(
								dc_ik.file,
								Constants._ploidy_H,
								founder_haps,
								nF1,
								dc_ik.start_end_position);
					}
				}

				mapCalc.put(dc[i][0].markers[0].replaceAll("_[0-9]{1,}$", ""), rfs);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				Thread t = Thread.currentThread();
				t.getUncaughtExceptionHandler().uncaughtException(t, e);
				e.printStackTrace();
				executor.shutdown();
				System.exit(1);
			}

			/**
			String contig = dc[i][0].markers[0].replaceAll("_[0-9]{1,}$", "");
			try {
				mapWriter.write("*"+contig+"\t"+
						median(kd_all)+"\t"+
						StatUtils.sum(kosambi)+"\t"+
						cat(kosambi, ",")+"\n");
			} catch (MathIllegalArgumentException | IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			 **/
		}
	}
		
	private class MyRfCalculator extends RfCalculator {
	
		public MyRfCalculator(int i, int j) {
			// TODO Auto-generated constructor stub
			super(i, j);
		}
	
		@Override
		public void run() {
			// TODO Auto-generated method stub
			
			try {
				MyPhasedDataCollection[] dc_i = dc[this.i];
				MyPhasedDataCollection[] dc_j = dc[this.j];
				String contig=null, contig2=null;
				int ii = 0;
				//String fi = null;
				while(ii<dc_i.length && contig==null) {
					contig = dc_i[ii]==null ? null : 
						dc_i[ii].markers[0].replaceAll("_[0-9]{1,}$", "");
					//fi = new File(dc_i[ii].file).getName().split("\\.")[1];
					ii++;
				}
				int jj = 0;
				//String fj = null;
				while(jj<dc_j.length && contig2==null) {
					contig2 = dc_j[jj]==null ? null : 
						dc_j[jj].markers[0].replaceAll("_[0-9]{1,}$", "");
					//fj = new File(dc_j[jj].file).getName().split("\\.")[1];
					jj++;
				}
				if(contig==null || contig2==null) return;
				if(conjPairRFs.containsKey(contig+Constants.scaff_collapsed_str+contig2) || 
						conjPairRFs.containsKey(contig2+Constants.scaff_collapsed_str+contig) )
					return;
				
				if(!scaff_only.isEmpty() && 
						!scaff_only.contains(contig) && 
						!scaff_only.contains(contig2)) return;
	
				int m = dc_i.length, n = dc_j.length;
				double[][] rf_all = new double[m*n][4];
				for(int k=0; k<rf_all.length; k++)
					Arrays.fill(rf_all[k], -1);
				
				// boolean executed = conj ? calcConjRFs(rf_all) : false;
				// if(!executed) {
				for(int k=0; k<m; k++) 
					for(int s=0; s<n; s++) 
						if(dc_i[k]!=null && dc_j[s]!=null)
							rf_all[k*m+s] = calcRFs(k, s);
				//}
				
				rf_all = Algebra.transpose(rf_all);
				StringBuilder os = new StringBuilder();
				os.append("#");
				os.append(this.i);
				os.append(" ");
				os.append(this.j);
				os.append("\n");
				for(int k=0; k<rf_all.length; k++) {
					os.append(formatter.format(rf_all[k][0]));
					for(int u=1; u<rf_all[k].length; u++) {
						os.append(" ");
						os.append(formatter.format(rf_all[k][u]));
					}
					os.append("\n");
				}
				double[] rf; 
				String w;
	
				rf = new double[4];
				for(int k=0; k<4; k++) {
					double[] rf_tmp = removeNEG(rf_all[k]);
					if(rf_tmp==null)
						rf[k] = -1;
					else
						rf[k] = StatUtils.min(rf_tmp);
				}
				w = StatUtils.min(rf)+"\t";
				for(int k=0; k<rf.length; k++) w += rf[k]+"\t";
				w += contig+"\t"+contig2+"\n";
				rfMinimumWriter.write(w);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				Thread t = Thread.currentThread();
				t.getUncaughtExceptionHandler().uncaughtException(t, e);
				e.printStackTrace();
				executor.shutdown();
				System.exit(1);
			}
		}
	
		/***
		private boolean calcConjRFs(final double[][] rf) {
			// TODO Auto-generated method stub
			MyPhasedDataCollection[] dc_i = dc[this.i];
			MyPhasedDataCollection[] dc_j = dc[this.j];

			int[] key4 = new int[4], key2 = new int[4];
			long key;
			int[][] hash_i = new int[2][nF1],
					hash_j = new int[2][nF1];

			int m = dc_i.length, n = dc_j.length;
			
			for(int k=0; k<m; k++) System.out.println(dc_i[k].file);
			for(int k=0; k<n; k++) System.out.println(dc_j[k].file);
			
			int z = 0;
			for(int k=0; k<m; k++) {
				if(dc_i[k]==null) continue;

				for(int l=0; l<n; l++) {
					if(dc_j[l]==null) continue;
					if(dc_i[k].file.equals(dc_j[l].file)) {
						Arrays.fill(rf[z], 0);
						for(int p=0; p<2; p++) {
							hash_i = dc_i[k].hashcode[p];
							hash_j = dc_j[l].hashcode[p];
							for(int u=0; u<nF1; u++) {
								key4[0] = hash_i[0][u];
								key4[1] = hash_i[1][u];
								key4[2] = hash_j[0][u];
								key4[3] = hash_j[1][u];

								key = ((((((long) key4[0]
										<<shift_bits2)+key4[1])
										<<shift_bits2)+key4[2])
										<<shift_bits2)+key4[3];

								if(rf_pool.containsKey(key)) {
									add(rf[z], rf_pool.get(key));
								} else {
									add(rf[z], key4, key2, key);

								}
							}
						}
						mininus(1, divide(rf[z], nF1*Constants._ploidy_H));
						z++;
					}
				}
			}
			return z>0;
		}
		**/
		
		private double[] calcRFs(int phase, int phase2) {
			// TODO Auto-generated method stub
			MyPhasedDataCollection dc_i = dc[i][phase];
			int[] key4 = new int[4], key2 = new int[4];
			long key;
			int[][] hash_i = new int[2][nF1],
					hash_j = new int[2][nF1];
	
			double[] rf = new double[4];
			boolean[][][][] data_j = dc[j][phase2].cloneData();
	
			for(int p=0; p<2; p++) {
	
				boolean[][][] data_j_p = data_j[p];
				hash_i = dc_i.hashcode[p];
	
				double[][] rf_all_p = new double[factorial_ploidy][4];
				for(int f=0; f<factorial_ploidy; f++) {
	
					next(data_j_p, f, hash_j);
					
					for(int n=0; n<nF1; n++) {
						key4[0] = hash_i[0][n];
						key4[1] = hash_i[1][n];
						key4[2] = hash_j[0][n];
						key4[3] = hash_j[1][n];
	
						key = ((((((long) key4[0]
								<<shift_bits2)+key4[1])
								<<shift_bits2)+key4[2])
								<<shift_bits2)+key4[3];
						
						
						if(rf_pool.containsKey(key)) {
							add(rf_all_p[f], rf_pool.get(key));
						} else {
							add(rf_all_p[f], key4, key2, key);
					
						}
					}
				}
				add(rf, max(rf_all_p));
			}
			return mininus(1, divide(rf, nF1*Constants._ploidy_H));
		}
	
		private void add(double[] ds, byte[] bs) {
			// TODO Auto-generated method stub
			for(int i=0; i<4; i++) ds[i] += bs[i];
		}
	
		private void add(double[] ds, int[] key4, int[] key2, long key) {
			// TODO Auto-generated method stub
			key2[0] = (key4[0]<<shift_bits2)+key4[2];
			key2[1] = (key4[0]<<shift_bits2)+key4[3];
			key2[2] = (key4[1]<<shift_bits2)+key4[2];
			key2[3] = (key4[1]<<shift_bits2)+key4[3];
			byte[] bsall = new byte[4];
			for(int i=0; i<4; i++)
				bsall[i] = rf_factory.get(key2[i]);
			add(ds, bsall);
			rf_pool.put(key, bsall);
		}
	
		private double sum(byte[] bs) {
			// TODO Auto-generated method stub
			double sum = 0;
			for(byte b : bs) sum += b;
			return sum;
		}
	
		private double min(byte[] bs) {
			// TODO Auto-generated method stub
			byte min = Byte.MAX_VALUE;
			for(byte b : bs)
				if(b<min) min = b;
			return min;
		}
	
		private void next(boolean[][][] data, int f, int[][] hash) {
			// TODO Auto-generated method stub
			final int i = johnson_trotter_permutation[f][0],
					i2 = johnson_trotter_permutation[f][1];
			boolean[] tmp;
			boolean[][] tmps;
			int key;
			for(int m=0; m<2; m++) {
				tmps = data[m];
				tmp = tmps[i];
				tmps[i] = tmps[i2];
				tmps[i2] = tmp;
				for(int n=0; n<nF1; n++) {
					key = 0;
					for(int p=0; p<Constants._ploidy_H; p++)
						key=(key<<mask_length)+(tmps[p][n] ? 1 : 0);
					hash[m][n] = key;
				}
			}
		}
	
		private double[] divide(double[] ds, double deno) {
			// TODO Auto-generated method stub
			for(int i=0; i<4; i++) ds[i] /= deno;
			return ds;
		}
		
		private double[] mininus(double d, double[] minus) {
			// TODO Auto-generated method stub
			for(int i=0; i<4; i++) minus[i] = d-minus[i];
			return minus;
		}
	
		private void divide(double[][][] rfs, int deno) {
			// TODO Auto-generated method stub
			for(int i=0; i<rfs.length; i++) 
				for(int j=0; j<rfs[i].length; j++)
					for(int k=0; k<rfs[i][j].length; k++)
						rfs[i][j][k] /= deno;
		}
	
		private void print(double[][] ds) {
			// TODO Auto-generated method stub
			//AnsiConsole.systemInstall();
	
			for(int i=0; i<ds.length; i++) {
				double m = StatUtils.min(ds[i]);
				for(int j=0; j<ds[i].length; j++) { 
					if(ds[i][j]==m) { 
						//System.out.print(ansi().fg(RED).
						//		a(formatter.format(ds[i][j])+"\t").reset());
						System.out.print(formatter.format(ds[i][j])+"\t");
						System.out.flush();
					} else {
						System.out.print(formatter.format(ds[i][j])+"\t");
						System.out.flush();
					}
				}
				System.out.println();
				System.out.flush();
			}
			System.out.println();
			System.out.flush();
			//AnsiConsole.systemUninstall();
		}
	
		private double[] search(double[][][] rfs) {
			// TODO Auto-generated method stub
	
			double[] rf = new double[rfs[0][0].length];
			for(int i=0; i<rf.length; i++) {
				double p1=0.0, p2=0.0;
				boolean shift1 = true, shift2 = true;
				while (true) {
					if(shift1) {
						p1 = rfs[0][0][i];
						for(int j=0; j<factorial_ploidy; j++) 
							if(rfs[0][j][i]<p1) 
								p1 = rfs[0][j][i];
						for(int x=0; x<rfs[0].length; x++)
							if(rfs[0][x][i]==p1)
								rfs[0][x][i] = Double.POSITIVE_INFINITY;
					}
					if(shift2) {
						p2 = rfs[1][0][i];
						for(int j=0; j<factorial_ploidy; j++) 
							if(rfs[1][j][i]<p2) 
								p2 = rfs[1][j][i];
						for(int x=0; x<rfs[0].length; x++)
							if(rfs[1][x][i]==p2)
								rfs[1][x][i] = Double.POSITIVE_INFINITY;
					}
					double d = Math.abs(p1-p2);
					if( Math.abs(d)>.1) {
						if(p1>p2) {
							shift2 = true;
							shift1 = false;
						} else {
							shift1 = true;
							shift2 = false;
						}
					} else if(p1==Double.POSITIVE_INFINITY ||
							p2==Double.POSITIVE_INFINITY) { 
						break;
					} else {
						break;
					}
				}
				rf[i] = p1+p2;
				if(rf[i]>.5) rf[i] = .5;
			}
			return rf;
		}
	
		private void add(double[] ds, double[] ds2) {
			// TODO Auto-generated method stub
			for(int i=0; i<4; i++) ds[i] += ds2[i];
		}
	
		private double[] min2(double[][] ds) {
			// TODO Auto-generated method stub
			double[] dss = new double[ds[0].length];
			Arrays.fill(dss, Double.POSITIVE_INFINITY);
			for(int i=0; i<ds.length; i++)
				for(int j=0; j<ds[i].length; j++)
					if(ds[i][j]<dss[j])
						dss[j] = ds[i][j];
			return dss;
		}
	
		private double[] min(double[][] ds) {
			// TODO Auto-generated method stub
			double tot=Double.POSITIVE_INFINITY, 
					rf=Double.POSITIVE_INFINITY;
			double f;
			int r = -1;
			int n = ds.length;
			for(int i=0; i<n; i++) 
				if( (f=StatUtils.min(ds[i]))<rf ||
						f==rf && StatUtils.sum(ds[i])<tot ) {
					rf = f;
					tot = StatUtils.sum(ds[i]);
					r = i;
				}
			return ds[r];
		}
	
	
		private double[] max(double[][] ds) {
			// TODO Auto-generated method stub
			double tot=Double.NEGATIVE_INFINITY, 
					rf=Double.NEGATIVE_INFINITY;
			double f;
			int r = -1;
			for(int i=0; i<ds.length; i++) 
				if( (f=StatUtils.max(ds[i]))>rf ||
						f==rf && StatUtils.sum(ds[i])>tot ) {
					rf = f;
					tot = StatUtils.sum(ds[i]);
					r = i;
				}
			return ds[r];
		}
	}

	private final Map<Integer, Byte> rf_factory = new HashMap<Integer, Byte>();
	private final Map<Long, byte[]> rf_pool = new ConcurrentHashMap<Long, byte[]>();

	@Override
	protected void initialise() {
		// TODO Auto-generated catch block
		super.initialise();
		this.initial_thread_pool();
		String[] scaff_all = new String[fileObj.keySet().size()];
		fileObj.keySet().toArray(scaff_all);
		this.dc = new MyPhasedDataCollection[scaff_all.length][best_n];
		for(int i=0; i<scaff_all.length; i++) {
			Set<FileObject> files = fileObj.get(scaff_all[i]);
			executor.submit(new MyFileLoader(scaff_all[i],
					files.toArray(new FileObject[files.size()]),
					i));
		}
		this.waitFor();

		myLogger.info("["+Utils.getSystemTime()+"] LOADING FILES DONE.");
		myLogger.info("["+Utils.getSystemTime()+"] READING LOG LIKELIHOOD DONE.");
	}
}


























