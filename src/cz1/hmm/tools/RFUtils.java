package cz1.hmm.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.math3.exception.MathIllegalArgumentException;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.inference.ChiSquareTest;
import org.apache.commons.math3.stat.inference.GTest;

import cz1.util.Algebra;
import cz1.util.Constants;
import cz1.util.Executor;
import cz1.util.Utils;

public abstract class RFUtils extends Executor {
	
	protected static final double kb = 1024;
	protected static final double mb = 1024*1024;
	protected static final double gb = 1024*1024*1024;
	
	protected String in_haps;
	protected String out_prefix;
	protected String[] founder_haps;
	protected String expr_id = null;
	protected int drop_thres = 1;
	protected double skew_phi = 2;
	protected int best_n = 10;
	protected final String goodness_of_fit = "fraction";
	
	protected double[] probs_uniform = new double[]{.5,.5,.5,.5};

	protected static NumberFormat formatter = new DecimalFormat("#0.000");
	protected static int nF1;
	
	
	protected final static Map<String, Set<FileObject>> fileObj = 
			new HashMap<String, Set<FileObject>>();
	protected final static Object lock = new Object();

	protected class FileObject {
		protected final File file;
		protected final String[] markers; 
		protected final int[] start_end_position;

		public FileObject(File file, 
				String[] markers,
				int[] start_end_position) {
			this.file = file;
			this.markers = markers;
			this.start_end_position = start_end_position;
		}
	}
	
	protected class FileExtraction implements Runnable {
		private final File[] files;

		public FileExtraction(File[] files) {
			this.files = files;
		}
		@Override
		public void run() {
			// TODO Auto-generated method stub
			List<String> scaff_all = new ArrayList<String>();
			String[][] markers = null;
			int[][] start_end_position = null;
			int scaff_n = 0;

			try {
				InputStream is = getInputStream(this.files[0].getAbsolutePath(), "SNP");
				BufferedReader br = Utils.getBufferedReader(is);
				List<List<String>> markers_all = new ArrayList<List<String>>();

				String marker = br.readLine().split("\\s+")[3];
				String scaff_prev = marker.replaceAll("_[0-9]{1,}$", ""),
						scaff;
				scaff_all.add(scaff_prev);
				String line;
				markers_all.add(new ArrayList<String>());
				int n_=0;
				markers_all.get(n_).add(marker);
				while( (line=br.readLine())!=null ) {
					marker = line.split("\\s+")[3];
					scaff = marker.replaceAll("_[0-9]{1,}$", "");
					if(scaff.equals(scaff_prev))
						markers_all.get(n_).add(marker);
					else {
						markers_all.add(new ArrayList<String>());
						n_++;
						markers_all.get(n_).add(marker);
						scaff_prev = scaff;
						scaff_all.add(scaff_prev);
					}
				}
				br.close();
				is.close();
				int cuv = 0;
				scaff_n = scaff_all.size();
				markers = new String[scaff_n][];
				start_end_position = new int[scaff_n][2];
				for(int i=0; i<scaff_n; i++) {
					markers[i] = new String[markers_all.get(i).size()];
					markers_all.get(i).toArray(markers[i]);
					int s = Integer.parseInt(markers[i][0].
							replaceAll(".*[^\\d](\\d+).*", "$1")),
							e = Integer.parseInt(markers[i][1].
									replaceAll(".*[^\\d](\\d+).*", "$1"));
					if(s<=e) {
						start_end_position[i][0] = cuv;
						cuv += markers[i].length;
						start_end_position[i][1] = cuv-1;
					} else {
						start_end_position[i][1] = cuv;
						cuv += markers[i].length;
						start_end_position[i][0] = cuv-1;
					}
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			for(int i=0; i<this.files.length; i++) 
				for(int j=0; j<scaff_n; j++) {
					String scaff = scaff_all.get(j); 
					synchronized(lock) {
						if(!fileObj.containsKey(scaff))
							fileObj.put(scaff, new HashSet<FileObject>());
						fileObj.get(scaff).add(new FileObject(
								this.files[i],
								markers[j],
								start_end_position[j]));
					}
				}
		}
	}
	
	protected class FileLoader implements Runnable {
		protected final String id;
		protected final FileObject[] files;
		protected final int i;

		public FileLoader(String id, FileObject[] files, int i) {
			this.id = id;
			this.files = files;
			this.i = i;
		}

		@Override
		public void run() {
			// TODO Auto-generated method stub

			double[] ll = new double[this.files.length];
			for(int k=0; k<ll.length; k++) 
				ll[k]=Double.NEGATIVE_INFINITY;
			int marker_n = this.files[0].markers.length;

			if( marker_n<2 ) {
				myLogger.warn("warning: "+
						this.id +" #marker is less than 2.");
				return;
			}

			InputStream is;
			for(int k=0; k<this.files.length; k++) {
				File file = this.files[k].file;
				try {
					if( (is = getInputStream(file.getAbsolutePath(), 
							"PHASEDSTATES"))==null ) {
						myLogger.warn("warning: "+
								file.getName()+
								" exsits, but phased states do not.");
						continue;
					} 
					
					BufferedReader br = Utils.getBufferedReader(is);
					br.readLine();
					String marker_str = br.readLine();
					br.close();
					is.close();
					
					if( marker_str==null ) {
						myLogger.warn("warning: "+
								file.getName()+
								" exists, but phased states are NULL.");
						continue;
					}
					double frac = marker_n/Double.parseDouble(marker_str);

					String line;
					if( (is = getInputStream(file.getAbsolutePath(), 
							"STDERR"))!=null ) {
						BufferedReader br2 = Utils.getBufferedReader(is);
						String lprob=null;
						while( (line=br2.readLine()) !=null) {
							if(line.startsWith("log"))
								lprob = line;
						}
						br2.close();
						is.close();
						if( lprob!=null ) {
							String[] s0 = lprob.split("\\s+");
							ll[k] = Double.parseDouble(s0[3])*frac;
						}
					} else {
						is = getInputStream(file.getAbsolutePath(), "PHASEDSTATES");
						BufferedReader br2 = Utils.getBufferedReader(is);
						String lprob = br2.readLine();
						br2.close();
						is.close();
						if( lprob!=null ) 
							ll[k] = Double.parseDouble(lprob)*frac;
						myLogger.info(ll[k]);
					}
				} catch (IOException e) {
					e.printStackTrace();
					System.exit(1);
				}
			}

			int[] maxN = maxN(ll);
			StringBuilder oos = new StringBuilder();
			boolean[] drop = new boolean[maxN.length];
			int dropped = 0;
			for(int k=0; k<maxN.length; k++) {

				int[] haps_observed = readHaplotypes(maxN[k]);

				long[] observed;
				double p;
				switch(goodness_of_fit) {
				case "fraction":
					double[] phases = new double[Constants._ploidy_H*2];
					for(int z=0; z<phases.length; z++) 
						phases[z] = (double) haps_observed[z];
					double expected = StatUtils.sum(phases)/Constants._ploidy_H/2;
					double maf = StatUtils.max(phases)/expected, 
							mif = StatUtils.min(phases)/expected;
					if( maf>1/skew_phi || mif<skew_phi) {
						myLogger.info(this.files[maxN[k]].file.getName()+
								" was dropped due to large haploptype frequency variance. (" +
								cat(phases, ",") +")");
						drop[k] = true;
					}
					if(drop[k]) dropped++;
					oos.append("["+(drop[k]?"drop](maf,":"keep](maf,")+maf+";mif,"+mif+") "+
							cat(haps_observed,",")+"\t"+
							this.files[maxN[k]].file.getName()+"\n");
					break;
				case "chisq":
					observed = new long[Constants._haplotype_z];
					for(int z=0; z<observed.length; z++) 
						observed[z] = (long) haps_observed[z];
					p = new ChiSquareTest().chiSquareTest(probs_uniform, observed);
					if(p<skew_phi) drop[k] = true;
					if(drop[k]) dropped++;
					oos.append("["+(drop[k]?"drop](p,":"keep](p,")+formatter.format(p)+") "+
							cat(haps_observed,",")+"\t"+
							this.files[maxN[k]].file.getName()+"\n");
					break;
				case "gtest":
					observed = new long[Constants._haplotype_z];
					for(int z=0; z<observed.length; z++) 
						observed[z] = (long) haps_observed[z];
					p = new GTest().gTest(probs_uniform, observed);
					if(p<skew_phi) drop[k] = true;
					if(drop[k]) dropped++;
					oos.append("["+(drop[k]?"drop](p,":"keep](p,")+formatter.format(p)+") "+
							cat(haps_observed,",")+"\t"+
							this.files[maxN[k]].file.getName()+"\n");
					break;
				default:
					throw new RuntimeException("Goodness-of-fit test should be fraction, "
							+ "chisq or gTest.");
				}
			}
			myLogger.info(oos.toString());
			myLogger.error(this.id+" - dropped "+dropped);
			if( drop.length-dropped<drop_thres ) {
				myLogger.error("Scaffold "+this.id+" dropped.");
			} else {
				int kk=0;
				for(int k=0; k<drop.length; k++) {
					if(!drop[k]) {
						FileObject fobj = this.files[maxN[k]];
						dc[i][kk] = new PhasedDataCollection(
								fobj.file.getName(),
								fobj.markers,
								fobj.start_end_position);
						kk++;
					}
					if(kk>=best_n) break;
				}
			}
		}

		private int[] readHaplotypes(final int i) {
			// TODO Auto-generated method stub
			try {
				InputStream is = getInputStream(this.files[i].file.getAbsolutePath(), 
						"PHASEDSTATES");
				BufferedReader br_states = Utils.getBufferedReader(is);
				String line, stateStr;
				String[] s;
				int[] haps_observed = new int[Constants._haplotype_z];
				while( (line=br_states.readLine())!=null ) {
					if(!line.startsWith("#")) continue;
					s = line.split("\\s+|:");
					stateStr = s[s.length-1];
					for(char h : stateStr.toCharArray())
						haps_observed[h>'9'?(h-'a'+9):(h-'1')]++;
				}
				br_states.close();
				is.close();
				return haps_observed;
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				System.exit(1);
			}
			return null;
		}

		private int[] maxN(double[] ll) {
			double[] ll0 = Arrays.copyOf(ll, ll.length);
			int n = ll.length;//best_n_phases[0].length;
			int[] maxN = new int[n];
			Arrays.fill(maxN, -1);
			for(int k=0; k<n; k++) {
				if(k>=ll0.length) return maxN;
				int p = 0;
				double e = Double.NEGATIVE_INFINITY;
				for(int s=0; s<ll0.length; s++)
					if(ll0[s]>e) {
						e = ll0[s];
						p = s;
					}
				maxN[k] = p;
				ll0[p] = Double.NEGATIVE_INFINITY;
			}
			return maxN;
		}

		private int[] maxN(double[] ll, int N) {
			double[] ll0 = Arrays.copyOf(ll, ll.length);
			int[] maxN = new int[N];
			Arrays.fill(maxN, -1);
			for(int k=0; k<N; k++) {
				if(k>=ll0.length) return maxN;
				int p = 0;
				double e = Double.NEGATIVE_INFINITY;
				for(int s=0; s<ll0.length; s++)
					if(ll0[s]>e) {
						e = ll0[s];
						p = s;
					}
				maxN[k] = p;
				ll0[p] = Double.NEGATIVE_INFINITY;
			}
			return maxN;
		}
	}
	
	protected final Map<String, double[][]> mapCalc = 
			new HashMap<String, double[][]>();
	
	protected class mapCalculator implements Runnable {

		private final int i;

		public mapCalculator(int i) {
			this.i = i;
		}

		@Override
		public void run() {
			// TODO Auto-generated method stub
			double[][] rfs = new double[dc[i].length][];
			
			for(int k=0; k<dc[this.i].length; k++) {
				PhasedDataCollection dc_ik= dc[i][k];
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
	
	protected double median(double[] ds) {
		// TODO Auto-generated method stub
		double[] ds0 = removeNEG(ds);
		if(ds0==null) return -1;
		Arrays.sort(ds0);
		int n = ds0.length;
		if (n % 2 == 0)
			return (ds0[n/2] + ds0[n/2-1])/2;
		else
			return ds0[n/2];
	}

	protected double[] removeNEG(double[] ds) {
		List<Double> ds0 = new ArrayList<Double>();
		for(double d : ds)
			if(d>=0)
				ds0.add(d);
		if(ds0.isEmpty()) return null;
		return ArrayUtils.toPrimitive(
				ds0.toArray(new Double[ds0.size()]));

	}
	
	private void fill(double[][] dss, 
			double d) {
		// TODO Auto-generated method stub
		for(double[] ds : dss) 
			Arrays.fill(ds, d);
	}
	
	protected static PhasedDataCollection[][] dc;
	
	protected class PhasedDataCollection {
		protected final String file;
		protected final String[] markers;
		protected final int[] start_end_position;

		public PhasedDataCollection(String file,
				String[] markers, 
				int[] start_end) {
			// TODO Auto-generated constructor stub
			this.file = file;
			this.markers = markers;
			if(markers.length!=
					Math.abs(start_end[0]-start_end[1])+1)
				throw new RuntimeException("!!!");
			this.start_end_position = start_end;
		}
	}
	
	public void calcGDsAll(String phasedStates, int ploidy, 
			String[] parents, int nF1, int[] start_end,  
			double[][] rfAll, int s) {
		// TODO Auto-generated method stub
		char[][] h = readHaplotypes(phasedStates, 
				ploidy, parents, nF1);
		int c = 0;
		if(start_end[0]<=start_end[1]) {
			for(int i=start_end[0]; i<=start_end[1]; i++) { 
				for(int j=i+1; j<=start_end[1]; j++) {
					double r = 0;
					for(int k=0; k<h.length; k++) 
						r += h[k][i]==h[k][j] ? 0 : 1;
					rfAll[c++][s] = r/h.length;
				}
			}
		} else {
			for(int i=start_end[0]; i>=start_end[1]; i--) { 
				for(int j=i-1; j>=start_end[1]; j--) {
					double r = 0;
					for(int k=0; k<h.length; k++) 
						r += h[k][i]==h[k][j] ? 0 : 1;
					rfAll[c++][s] = r/h.length;
				}
			}
		}
	}

	public static double[] calcGDs(String phasedStates, int ploidy, 
			String[] parents, int nF1, int[] start_end) {
		// TODO Auto-generated method stub
		char[][] h = readHaplotypes(phasedStates, ploidy, parents, nF1);
		if(start_end[0]<=start_end[1]) {
			double[] d = new double[start_end[1]-start_end[0]+1];
			for(int i=start_end[0]; i<start_end[1]; i++) {
				double c = 0;
				for(int j=0; j<h.length; j++) 
					c += h[j][i]==h[j][i+1] ? 0 : 1;
				//d[i] = geneticDistance( c/h.length, mapFunc);
				d[i-start_end[0]] = c/h.length;
			}
			return d;
		} else {
			double[] d = new double[start_end[0]-start_end[1]+1];
			for(int i=start_end[0]; i>start_end[1]; i--) {
				double c = 0;
				for(int j=0; j<h.length; j++) 
					c += h[j][i]==h[j][i-1] ? 0 : 1;
				//d[i] = geneticDistance( c/h.length, mapFunc);
				d[start_end[0]-i] = c/h.length;
			}
			return d;
		}
	}

	public static double calcGD(String phasedStates, int ploidy,
			String[] parents, int nF1, int[] start_end) {
		char[][] h = readHaplotypes(phasedStates, ploidy, parents, nF1);
		double c = 0;
		for(int i=0; i<h.length; i++)
			c += h[i][start_end[0]]==h[i][start_end[1]] ? 0 : 1;
		//return geneticDistance( c/h.length, mapFunc);
		return c/h.length;	
	}

	protected static char[][] readHaplotypes(String phasedStates, int ploidy,
			String[] parents, int nF1) {
		// TODO Auto-generated method stub
		try {
			BufferedReader br = Utils.getBufferedReader(phasedStates);
			br.readLine();
			int m = Integer.parseInt(br.readLine());
			char[][] h = new char[nF1*ploidy][m];
			String line, stateStr;
			String[] s;
			int c = 0;
			while( (line=br.readLine())!=null ) {
				if(!line.startsWith("#")) continue;
				//if(skip++<2) continue;
				s = line.split("\\s+|:");
				if(Arrays.asList(parents).contains(s[2])) continue;
				stateStr = s[s.length-1];
				h[c++] = stateStr.toCharArray();
			}
			br.close();
			return h;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			System.exit(1);
		}

		return null;
	}
	
	protected static double geneticDistance(double r, String mapFunc) {
		// TODO Auto-generated method stub
		switch(mapFunc.toUpperCase()) {
		case "KOSAMBI":
			return .25*Math.log((1+2*r)/(1-2*r));
		case "HALDANE":
			return -.5*Math.log(1-2*r);	
		default:
			throw new RuntimeException("Undefined genetic mapping function.");
		}
	}

	public InputStream getInputStream(
			String root,
			String file) {
		// TODO Auto-generated method stub
		ZipFile in;
		try {
			in = new ZipFile(root);
			String target = null;
			switch(file.toUpperCase()) {
			case "PHASEDSTATES":
				target = "phasedStates/"+expr_id+".txt";
				break;
			case "STDERR":
				target = "stderr_true";
				break;
			case "EMISS":
				target = "results_hmm/emissionModel.txt";
				break;
			case "TRANS":
				target = "results_hmm/transitionModel.txt";
				break;
			case "SNP":
				target = "snp_"+expr_id+".txt";
				break;
			}
			if(in.getEntry(target)==null) {
				in.close();
				return null;
			}
			final InputStream is = in.getInputStream(in.getEntry(target));
			return is;
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}
	
	protected static String cat(double[] array, String sep) {
		String s = ""+array[0];
		for(int i=1; i<array.length; i++)
			s += sep+array[i];
		return s;
	}

	protected static String cat(int[] array, String sep) {
		String s = ""+array[0];
		for(int i=1; i<array.length; i++)
			s += sep+array[i];
		return s;
	}
}