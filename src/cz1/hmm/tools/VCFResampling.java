package cz1.hmm.tools;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.math3.stat.inference.TestUtils;
import org.apache.commons.math3.stat.StatUtils;

import java.util.Map;

import cz1.breeding.data.FullSiblings;
import cz1.hmm.data.DataCollection;
import cz1.hmm.data.DataEntry;
import cz1.util.Algebra;
import cz1.util.ArgsEngine;
import cz1.util.Combination;
import cz1.util.Constants;
import cz1.util.Executor;


public class VCFResampling extends Executor {
	
	private int ploidy = 2;
	private String data_in = null;
	private String data_out = null;
	private int window_size = 100000;
	private int min_snp = 5;
	private String[] founder_haps = null;
	
	public VCFResampling() {}
	
	public VCFResampling(int ploidy, 
			String data_in, 
			String data_out) {
		this.ploidy = ploidy;
		this.data_in = data_in;
		this.data_out = data_out;
	}

	@Override
	public void printUsage() {
		// TODO Auto-generated method stub
		myLogger.info(
				"\n\nUsage is as follows:\n"
						+ " -i/--data-in          Input zip data file.\n"
						+ " -p/--ploidy           Ploidy of the genome (default 2).\n"
						+ " -w/--window-size      Window size. (default 100Kb)\n"
						+ "                       The program will try to select one SNP in each window. \n"
						+ "                       This parameter recognises common suffix including,\n"
						+ "                       K, k, Kb, kb, M, m, Mb, mb, G, g, Gb, gb.\n"
						+ " -0/--min-snp-number   Minumum SNP number for a contig/scaffold (default 5)\n"
						+ " -f/--founder-haps     Parent sample names seperated by \':\'.\n"
						+ " -o/--data-out         Output zip data file (default \'in_prefix\'.resampled.zip).\n\n");
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
			myArgsEngine.add("-i", "--data-in", true);
			myArgsEngine.add("-p", "--ploidy", true);
			myArgsEngine.add("-w", "--window-size", true);
			myArgsEngine.add("-0", "--min-snp-number", true);
			myArgsEngine.add("-f", "--founder-haps", true);
			myArgsEngine.add("-o", "--data-out", true);
			myArgsEngine.parse(args);
		}

		if (myArgsEngine.getBoolean("-i")) {
			data_in = myArgsEngine.getString("-i");
		} else {
			printUsage();
			throw new IllegalArgumentException("Please specify your VCF file.");
		}
		
		if (myArgsEngine.getBoolean("-o")) {
			data_out = myArgsEngine.getString("-o");
		} else {
			data_out = data_in.replaceAll(".zip$", ".resampled.zip");
		}
		
		if (myArgsEngine.getBoolean("-p")) {
			ploidy = Integer.parseInt(myArgsEngine.getString("-p"));
			Constants._ploidy_H = ploidy;
		}
		
		if (myArgsEngine.getBoolean("-w")) {
			String w_str = myArgsEngine.getString("-w");
			Pattern p = Pattern.compile("(^\\d+)(.*?$)");
			Matcher m = p.matcher(w_str);
			m.find();
			int w_val = Integer.parseInt(m.group(1));
			String w_unit = m.group(2);
			int multiplier = 1;
			if(!w_unit.equals(""))
				switch(w_unit) {
				case "K":
				case "k":
				case "Kb":
				case "kb":
					multiplier = 1000;
					break;
				case "M":
				case "m":
				case "Mb":
				case "mb":
					multiplier = 1000000;
					break;
				case "G":
				case "g":
				case "Gb":
				case "gb":
					multiplier = 1000000000;
					break;
				default:
					throw new RuntimeException("unknown multiplier!!! \n    "
							+ "Accepted suffix: K, k, Kb, kb, M, m, Mb, mb, G, g, Gb, gb.\n");
				}
			window_size = w_val*multiplier;
			System.out.println(window_size);
		}
		
		if (myArgsEngine.getBoolean("-f")) {
			founder_haps = myArgsEngine.getString("-f").split(":");
		} else {
			printUsage();
			throw new IllegalArgumentException("Please specify the parent samples.");
		}
		
		if (myArgsEngine.getBoolean("-0")) {
			min_snp = Integer.parseInt(myArgsEngine.getString("-0"));
		}
	}
	
	final Map<String, int[]> snp_out = new HashMap<String, int[]>();
	
	@Override
	public void run() {
		// TODO Auto-generated method stub
		FullSiblings fullSib = new FullSiblings(this.ploidy);
		
		final List<String> contigs = DataCollection.getContigList(data_in);
		final List<String> samples = DataCollection.getSampleList(data_in);
		int[] founder_index = new int[2];
		Arrays.fill(founder_index, -1);
		for(int i=0; i!=samples.size(); i++) {
			if(samples.get(i).equals(founder_haps[0]))
				founder_index[0] = i;
			else if (samples.get(i).equals(founder_haps[1]))
				founder_index[1] = i;
		}

		for(String contig : contigs) {
			DataEntry data = DataCollection.readDataEntry(data_in, 
					new String[] {contig})[0];
			double[] position = data.getPosition();
			int no = position.length;
			if(no<=min_snp) {
				int[] out = new int[no];
				for(int i=0; i!=no; i++) out[i] = i;
				snp_out.put(contig, out);
				continue;
			}
			
			double[] segs = fullSib.calcSegs(data, founder_index);
			int l = 0;
			
			final Map<Integer, List<Integer>> bins = new HashMap<Integer, List<Integer>>();
			for(int i=0; i!=no; i++) {
				while( (l+1)*window_size<position[i] ) l++;
				if(!bins.containsKey(l)) bins.put(l, new ArrayList<Integer>());
				bins.get(l).add(i);
			}
			
			List<Integer> selected = new ArrayList<Integer>();
			final Map<Integer, Iterator<Integer>> bins_it = 
					new HashMap<Integer, Iterator<Integer>>();
			for(Map.Entry<Integer, List<Integer>> entry : bins.entrySet()) {
				Collections.sort(entry.getValue(), new Comparator<Integer>() {
					@Override
					public int compare(Integer i0, Integer i1) {
						// TODO Auto-generated method stub
						return Double.compare(segs[i0], segs[i1]);
					}
				});
				Iterator<Integer> it = entry.getValue().iterator();
				selected.add(it.next());
				if(it.hasNext()) bins_it.put(entry.getKey(), it);
			}
			
			while(selected.size()<min_snp) {
				List<Integer> candidates = new ArrayList<Integer>();
				Set<Integer> key_rm = new HashSet<Integer>();
				for(Map.Entry<Integer, Iterator<Integer>> entry : bins_it.entrySet()) {
					Iterator<Integer> it = entry.getValue();
					candidates.add(it.next());
					if(!it.hasNext()) key_rm.add(entry.getKey());
				}
				if(candidates.size()==0) break;
				for(Integer i : key_rm) bins_it.remove(i);
				Collections.sort(candidates, new Comparator<Integer>() {
					@Override
					public int compare(Integer i0, Integer i1) {
						// TODO Auto-generated method stub
					    return Double.compare(segs[i0], segs[i1]);
                    }
				});
				for(Integer i : candidates) {
					if(selected.size()<min_snp) 
						selected.add(i);
					else break;
				}
			}
		
            Collections.sort(selected);    
			snp_out.put(contig, 
					ArrayUtils.toPrimitive(selected.toArray(new Integer[selected.size()])));
		}
		
		int N = 0;
		for(Map.Entry<String, int[]> entry : snp_out.entrySet()) 
			N += entry.getValue().length;
		System.err.println(N+" SNPs reserved.");
		write();
	}

	private void write() {
		// TODO Auto-generated method stub
		try {
			ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new 
					FileOutputStream(data_out), 65536));
			final ZipFile zin = new ZipFile(data_in);
			final Enumeration<? extends ZipEntry> entries = zin.entries();
			while ( entries.hasMoreElements() ) {
				final ZipEntry entry = entries.nextElement();
				final String entry_name = entry.getName();
				zout.putNextEntry(new ZipEntry(entry_name));
				
				if(entry_name.equals("samples") ) {	
					write(zout, zin.getInputStream(entry), null);
				} else if(entry_name.equals("contig")) {
					final List<String> compound_contig_id = new ArrayList<String>();
					for(Map.Entry<String, int[]> entry2 : snp_out.entrySet()) 
						compound_contig_id.add(
								String.format("%010d", entry2.getValue().length)+
								entry2.getKey() );
					Collections.sort(compound_contig_id);
					Collections.reverse(compound_contig_id);
					for(String cid : compound_contig_id) {
						String contig = cid.substring(10);
						zout.write((contig+"\t"+snp_out.get(contig).length+
								Constants.line_sep).getBytes());
					}
				} else if(!entry_name.endsWith(Constants.file_sep)) {
					final String contig = entry_name.split(Constants.file_sep)[0];
					write(zout, zin.getInputStream(entry), snp_out.get(contig));
				} else {
					continue;
				}
			}
			zin.close();
			zout.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}
	
    private void write(ZipOutputStream zout, InputStream is, int[] lines) {
		// TODO Auto-generated method stub
		try {
			if(lines==null) {
				IOUtils.copy(is, zout);
			} else {
				final BufferedReader br = new BufferedReader(new InputStreamReader(is));
				String line;
				int l = 0, k = 0;
				while( (line=br.readLine())!=null ) {
					if(l==lines[k]) {
						zout.write((line+Constants.line_sep).getBytes());
						++k;
						if(k>=lines.length) break;
					}
					l++;
				}
				br.close();
			}
			is.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}