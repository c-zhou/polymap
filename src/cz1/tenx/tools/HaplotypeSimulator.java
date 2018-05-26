package cz1.tenx.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;

import cz1.ngs.model.Sequence;
import cz1.util.ArgsEngine;
import cz1.util.Constants;
import cz1.util.Executor;
import cz1.util.Utils;

public class HaplotypeSimulator extends Executor {

	private String vcf_in;
	private String hap_in;
	private String out_f;
	private int hap_copy = 2;
	
	@Override
	public void printUsage() {
		// TODO Auto-generated method stub
		myLogger.info(
				"\n\nUsage is as follows:\n"
						+ " -f/--vcf            Input VCF file.\n"
						+ " -p/--hap            Input reference haplotype file.\n"
						+ " -n/--copy           Copy of haplotypes.\n"
						+ " -o/--prefix         Output files prefix.\n\n");	
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
			myArgsEngine.add("-f", "--vcf", true);
			myArgsEngine.add("-p", "--hap", true);
			myArgsEngine.add("-n", "--copy", true);
			myArgsEngine.add("-o", "--prefix", true);
			myArgsEngine.parse(args);
		}

		if (myArgsEngine.getBoolean("-f")) {
			vcf_in = myArgsEngine.getString("-f");
		} else {
			printUsage();
			throw new IllegalArgumentException("Please specify your VCF file.");
		}
		
		if (myArgsEngine.getBoolean("-p")) {
			hap_in = myArgsEngine.getString("-p");
		} else {
			printUsage();
			throw new IllegalArgumentException("Please specify your haplotype file.");
		}
		
		if (myArgsEngine.getBoolean("-n")) {
			hap_copy = Integer.parseInt(myArgsEngine.getString("-n"));
		}
		
		if (myArgsEngine.getBoolean("-o")) {
			out_f = myArgsEngine.getString("-o");
		} else {
			printUsage();
			throw new IllegalArgumentException("Please specify your output file prefix.");
		}
	}

	@Override
	public void run() {
		// TODO Auto-generated method stub
		List<Sequence> hapseqs = Sequence.parseFastaFileAsList(hap_in);
		if(hapseqs.size()>1) 
			throw new RuntimeException("Multiple haplotype sequence not supported!!!");
		final String seq_sn = hapseqs.get(0).seq_sn();
		final String hapseq = hapseqs.get(0).seq_str();
		final StringBuilder[] haps = new StringBuilder[hap_copy];
		// we initiate a StringBuilder of size slightly greater than the reference haplotype
		for(int i=0; i<hap_copy; i++) haps[i] = new StringBuilder((int)(1.1*hapseq.length()));
		try {
			BufferedReader br_vcf = Utils.getBufferedReader(vcf_in);
			String line;
			String[] s, s2;
			int pos, refz, offset = 0, h;
			
			BufferedWriter bw_hap = Utils.getBufferedWriter(out_f+".hap");
			bw_hap.write("#CHROM\tPOS\tID\tREF\tALT");
			for(int i=0; i<hap_copy; i++) bw_hap.write("\t"+i);
			bw_hap.write("\n");
			
			while( (line=br_vcf.readLine())!=null ) {
				if(line.startsWith("#")) continue;
				s = line.split("\\s+");
				pos = Integer.parseInt(s[1])-1;
				if(pos<offset) continue;
				s2 = s[4].split(",");
				final String[] alleles = new String[1+s2.length];
				alleles[0] = s[3];
				System.arraycopy(s2, 0, alleles, 1, s2.length);
				refz = alleles[0].length();
				
				if(!hapseq.substring(pos,pos+refz).equals(alleles[0])) {
					alleles[0] = Sequence.revCompSeq(alleles[0]);
					if(!hapseq.substring(pos,pos+refz).equals(alleles[0])) 
						// throw new RuntimeException(pos+","+refz+","+alleles[0]+"!!!");
						continue;
					for(int i=1; i<alleles.length; i++)
						alleles[i] = Sequence.revCompSeq(alleles[i]);	
				}
				
				bw_hap.write(seq_sn+"\t"+(pos+1)+"\t"+s[2]+"\t"+alleles[0]+"\t"+altAlleles(alleles));
				
				for(int i=0; i<hap_copy; i++) {
					haps[i].append(hapseq.substring(offset, pos));
					if(i<alleles.length) {
						// guarantee one copy of each allele 
						h = i;
					} else {
						// randomly select alleles for the remaining copies of haplotype
						h = Constants.rand.nextInt(alleles.length);
					}
					haps[i].append(alleles[h]);
					bw_hap.write("\t"+h);
				}
				offset = pos+refz;
			}
			// append subsequence to the hapseq end
			for(int i=0; i<hap_copy; i++) haps[i].append(hapseq.substring(offset));
			br_vcf.close();
			bw_hap.close();
			
			// now write output files
			for(int i=0; i<hap_copy; i++) {
				BufferedWriter bw = Utils.getBufferedWriter(out_f+".hap."+i+".fasta");
				bw.write(Sequence.formatOutput(seq_sn+"_HAP"+i, 
						haps[i].toString()));
				bw.close();
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private String altAlleles(String[] alleles) {
		// TODO Auto-generated method stub
		StringBuilder str = new StringBuilder(alleles[1]);
		for(int i=2; i<alleles.length; i++) {
			str.append(",");
			str.append(alleles[i]);
		}
		return str.toString();
	}
}
