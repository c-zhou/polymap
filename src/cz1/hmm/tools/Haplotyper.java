package cz1.hmm.tools;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

import javax.imageio.ImageIO;
import javax.swing.JFrame;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;

import com.itextpdf.awt.PdfGraphics2D;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.PdfContentByte;
import com.itextpdf.text.pdf.PdfTemplate;
import com.itextpdf.text.pdf.PdfWriter;

import cz1.hmm.data.DataCollection;
import cz1.hmm.data.DataEntry;
import cz1.hmm.model.HiddenMarkovModel;
import cz1.hmm.model.HMMTrainer;
import cz1.hmm.swing.HMMFrame;
import cz1.hmm.swing.HMMPanel;
import cz1.hmm.swing.Printer;
import cz1.util.ArgsEngine;
import cz1.util.Constants;
import cz1.util.Constants.Field;
import cz1.util.Executor;

public class Haplotyper extends Executor {

	private HMMFrame hmmf = null;
	private HMMPanel hmmp = null;
	private String in_zip = null;
	private String out_prefix = null;
	private String[] scaff = null;
	private double[] seperation = null;
	private boolean trainExp = false;
	private boolean[] reverse = new boolean[]{false};
	private int max_iter = 100;
	private Field field = Field.PL;
	private String plot_pdf = null;
	private String expr_id = null;
	private int[] start_pos = null;
	private int[] end_pos = null;
	private String hmm_file = null;
	private int resampling = 100;
	private double founder_hap_coeff = 0.5;
	private double loglik_diff = 0.05129329;
	
	public Haplotyper() {}
	
	public Haplotyper(String in_zip,
			String out_prefix,
			String[] scaff,
			double[] seperation,
			boolean trainExp,
			boolean[] reverse,
			int max_iter,
			int ploidy,
			Field field) {
		this.in_zip = in_zip;
		this.out_prefix = out_prefix;
		this.scaff = scaff;
		this.seperation = seperation;
		this.trainExp = trainExp;
		this.reverse = reverse;
		this.max_iter = max_iter;
		Constants.ploidy(ploidy);
		this.field = field;
		this.expr_id = new File(in_zip).getName().
				replaceAll(".zip$", "").
				replace(".", "").
				replace("_", "");
	}
	
	public Haplotyper(String in_zip,
			String out_prefix,
			String[] scaff,
			double[] seperation,
			boolean trainExp,
			boolean[] reverse,
			int max_iter,
			int ploidy,
			Field field,
			String expr_id) {
		this.in_zip = in_zip;
		this.out_prefix = out_prefix;
		this.scaff = scaff;
		this.seperation = seperation;
		this.trainExp = trainExp;
		this.reverse = reverse;
		this.max_iter = max_iter;
		Constants.ploidy(ploidy);
		this.field = field;
		this.expr_id = expr_id;
	}
	
	public Haplotyper(String in_zip,
			String out_prefix, 
			String[] scaff,
			int ploidy, 
			Field field) {
		// TODO Auto-generated constructor stub
		this.in_zip = in_zip;
		this.out_prefix = out_prefix;
		this.scaff = scaff;
		Constants.ploidy(ploidy);
		this.field = field;
		this.expr_id = new File(in_zip).getName().
				replaceAll(".zip$", "").
				replace(".", "").
				replace("_", "");
	}
	
	public Haplotyper(String in_zip,
			String out_prefix, 
			String[] scaff,
			int ploidy, 
			Field field,
			String expr_id,
			int max_iter) {
		// TODO Auto-generated constructor stub
		this.in_zip = in_zip;
		this.out_prefix = out_prefix;
		this.scaff = scaff;
		Constants.ploidy(ploidy);
		this.field = field;
		this.expr_id = expr_id;
		this.max_iter = max_iter;
	}
	
	public Haplotyper(String in_zip,
			String out_prefix, 
			String scaff,
			String seperation,
			String reverse,
			int ploidy, 
			Field field,
			String expr_id,
			int max_iter,
			boolean trainExp) {
		// TODO Auto-generated constructor stub
		this.in_zip = in_zip;
		this.out_prefix = out_prefix;
		this.scaff = scaff.split(":");
		String[] s = seperation.split(":");
		this.seperation = new double[s.length];
		for(int i=0; i<s.length; i++) 
			this.seperation[i] = Double.parseDouble(s[i]);
		s = reverse.split(":");
		this.reverse = new boolean[s.length];
		for(int i=0; i<s.length; i++) 
			this.reverse[i] = Boolean.parseBoolean(s[i]);
		Constants.ploidy(ploidy);
		this.field = field;
		this.expr_id = expr_id;
		this.max_iter = max_iter;
		this.trainExp = trainExp;
	}

	@Override
	public void printUsage() {
		// TODO Auto-generated method stub
		myLogger.info(
				"\n\nUsage is as follows:\n"
							+" -i/--input                   Input zipped file.\n"
							+" -o/--prefix                  Output file location.\n"
							+" -ex/--experiment-id          Common prefix of haplotype files for this experiment.\n"
							+" -hf/--hmm-file               A zipped HMM file. If provided the initial transition and \n"
							+"                              emission probabilities will be read from the file instead \n"
							+"                              of randomly selected. \n"
							+" -c/--scaffold                The scaffold/contig/chromosome id will run.\n"
							+" -cs/--start-position         The start position of the scaffold/contig/chromosome.\n"
							+" -ce/--end-position           The end position of the scaffold/contig/chromosome.\n"
							+" -x/--max-iter                Maxmium rounds for EM optimization (default 100).\n"
							+" -p/--ploidy                  Ploidy of genome (default 2).\n"
							+" -f/--parent                  Parent samples (separated by a \":\").\n"
							+" -s/--initial-seperation      Initialisations of distances between the adjacent scaffolds \n"
							+"                              if multiple scaffolds will be jointly inferred. The separation \n"
							+"                              could be either physical distances or recombination frequencies, \n"
							+"                              i.e., if all values provided is below 0.5, the \n"
							+"                              program will take them as recombination frequencies. \n"
							+"                              Distances should be separated by \":\".\n"
							+" -r/--reverse                 Take either 'true' or 'false', indicating whetherr the \n"
							+"                              scaffold is reversed before inferring haplotypes. Multiple \n"
							+"                              scaffolds are separated by \":\".\n"
							+" -G/--genotype                Use genotypes to infer haplotypes. Mutually exclusive with \n"
							+"                              option -D/--allele-depth and -L/--genetype likelihood.\n"
							+" -D/--allele-depth            Use allele depth to infer haplotypes. Mutually exclusive \n"
							+"                              with option -G/--genotype and -L/--genetype likelihood.\n"
							+" -L/--genotype-likelihood     Use genotype likelihoods to infer haplotypes. Mutually \n"
							+"                              exclusive with option -G/--genotype and -L/--allele-depth \n"
							+"                              (default).\n"
							+" -e/--train-exp               Re-estimate transition probabilities between founder/parental \n"
							+"                              haplotypes at each step.\n"
							+" -S/--random-seed             Random seed for this run.\n"
							+" -pp/--print-plot             Plot the hidden Markov model.\n"
							+" -sp/--save-plot              Save the plot as a pdf file. The file name should be provided here.\n"
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
			myArgsEngine.add("-i", "--input", true);
			myArgsEngine.add("-o", "--prefix", true);
			myArgsEngine.add("-ex", "--experiment-id", true);
			myArgsEngine.add("-hf", "--hmm-file", true);
			myArgsEngine.add("-c", "--scaffold", true);
			myArgsEngine.add("-cs", "--start-position", true);
			myArgsEngine.add("-ce", "--end-position", true);
			myArgsEngine.add("-x", "--max-iter", true);
			myArgsEngine.add("-p", "--ploidy", true);
			myArgsEngine.add("-f", "--parent", true);
			myArgsEngine.add("-s", "--initial-seperation", true);
			myArgsEngine.add("-r", "--reverse", true);
			myArgsEngine.add("-G", "--genotype", false);
			myArgsEngine.add("-D", "--allele-depth", false);
			myArgsEngine.add("-L", "--genotype-likelihood", false);
			myArgsEngine.add("-e", "--train-exp", false);
			myArgsEngine.add("-S", "--random-seed", true);
			myArgsEngine.add("-pp", "--print-plot", false);
			myArgsEngine.add("-sp", "--save-plot", true);
			myArgsEngine.parse(args);
		}
		
		if(myArgsEngine.getBoolean("-i")) {
			in_zip = myArgsEngine.getString("-i");
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
			expr_id = new File(in_zip).getName().
					replaceAll(".zip$", "").
					replace(".", "").
					replace("_", "");
		}
		
		if(myArgsEngine.getBoolean("-hf")) {
			hmm_file = myArgsEngine.getString("-hf");
		}
		
		if(myArgsEngine.getBoolean("-c")) {
			scaff = myArgsEngine.getString("-c").split(":");
		}  else {
			printUsage();
			throw new IllegalArgumentException("Please specify the scaffold(s).");
		}
		
		if( myArgsEngine.getBoolean("-cs") ^ myArgsEngine.getBoolean("-ce") ) {
			printUsage();
			throw new IllegalArgumentException("Need to specify both start position "
					+ "and end position or none of them.");
		}
		
		if(myArgsEngine.getBoolean("-cs")) {
			String[] s = myArgsEngine.getString("-cs").split(":");
			start_pos = new int[scaff.length];
			int n = Math.min(scaff.length, s.length);
			for(int i=0; i!=n; i++) 
				start_pos[i] = Integer.parseInt(s[i]);
			for(int i=n; i<start_pos.length; i++)
				start_pos[i] = Integer.MIN_VALUE;
		}
		
		if(myArgsEngine.getBoolean("-ce")) {
			String[] s = myArgsEngine.getString("-ce").split(":");
			end_pos = new int[scaff.length];
			int n = Math.min(scaff.length, s.length);
			for(int i=0; i!=n; i++) 
				end_pos[i] = Integer.parseInt(s[i]);
			for(int i=n; i<end_pos.length; i++)
				end_pos[i] = Integer.MAX_VALUE;
		}
		
		if(myArgsEngine.getBoolean("-x")) {
			max_iter = Integer.parseInt(myArgsEngine.getString("-x"));
		}
		
		if(myArgsEngine.getBoolean("-p")) {
			Constants.ploidy(Integer.parseInt(myArgsEngine.getString("-p")));
		}
		
		if(myArgsEngine.getBoolean("-f")) {
			Constants._founder_haps = myArgsEngine.getString("-f");
		} else {
			printUsage();
			throw new IllegalArgumentException("Please specify the parent samples (seperated by a \":\").");
		}
		
		if(myArgsEngine.getBoolean("-s")) {
			String[] ss = myArgsEngine.getString("-s").split(":");
			if(ss.length<scaff.length-1)
				throw new RuntimeException("Number of scaffolds does not match number of initial seperations!!!");
			seperation = new double[scaff.length-1];
			for(int i=0; i<seperation.length; i++)
				seperation[i] = Double.parseDouble(ss[i]);
		} else {
			seperation = new double[scaff.length-1];
			for(int i=0; i<seperation.length; i++)
				seperation[i] = Math.max(Math.round(
						Constants.rand.nextDouble()*
						Constants._max_initial_seperation),1);
		}
		boolean isRF = Constants.isRF(seperation);
		if(isRF) {
			for(int i=0; i<seperation.length; i++) 
				seperation[i] = Constants.haldane(seperation[i]);
		}
		
		if(myArgsEngine.getBoolean("-r")) {
			String[] dd = myArgsEngine.getString("-r").split(":");
			if(dd.length<scaff.length)
				throw new RuntimeException("Number of scaffolds does not match number of reverses!!!");
			reverse = new boolean[scaff.length];
			for(int i=0; i<reverse.length; i++)
				reverse[i] = Boolean.parseBoolean(dd[i]);
		} else {
			reverse = new boolean[scaff.length];
			Arrays.fill(reverse, false);
		}
		
		int i = 0;
		if(myArgsEngine.getBoolean("-G")) {
			field = Field.GT;
			i++;
		}
		
		if(myArgsEngine.getBoolean("-D")) {
			field = Field.AD;
			i++;
		}
		
		if(myArgsEngine.getBoolean("-L")) {
			field = Field.PL;
			i++;
		}
		if(i>1) throw new RuntimeException("Options -G/--genotype, "
				+ "-D/--allele-depth, and -L/--genotype-likelihood "
				+ "are exclusive!!!");
		
		if(myArgsEngine.getBoolean("-S")) {
			Constants.seed = Long.parseLong(myArgsEngine.getString("-S"));
			Constants.setRandomGenerator();
		}
		
		if(myArgsEngine.getBoolean("-e")) {
			trainExp = true;
		}
		
		if(myArgsEngine.getBoolean("-pp")) {
			Constants.plot(true);
		}
		
		if(myArgsEngine.getBoolean("-sp")) {
			plot_pdf = myArgsEngine.getString("-sp");
			Constants.plot(true);
			Constants.printPlots(true);
		}
	}

	@Override
	public void run() {
		// TODO Auto-generated method stub
		myLogger.info("Random seed - "+Constants.seed);

		
		DataEntry[] de = start_pos==null ?
			DataCollection.readDataEntry(in_zip, scaff) :
			DataCollection.readDataEntry(in_zip, scaff, start_pos, end_pos);

				// DataEntry[] de = DataCollection.readDataEntry(in_zip, Constants._ploidy_H);
		final HiddenMarkovModel hmm = (hmm_file==null ?
			new HMMTrainer(de, seperation, reverse, trainExp, field, founder_hap_coeff):
			new HMMTrainer(de, seperation, reverse, trainExp, field, founder_hap_coeff, hmm_file));
		
		if(Constants.plot()){
			Runnable run = new Runnable(){
				public void run(){
					try {
						hmmf = new HMMFrame();
						hmmf.clearTabs();
						if(Constants.showHMM) 
							hmmp = hmmf.addHMMTab(hmm, hmm.de(), 
									new File(out_prefix));
					} catch (Exception e) {
						Thread t = Thread.currentThread();
						t.getUncaughtExceptionHandler().uncaughtException(t, e);
						e.printStackTrace();
					}
				}
			};
			Thread th = new Thread(run);
			th.run();
		}

		if(Constants.plot()){
			hmmf.pack();
			hmmf.setVisible(true);
		}

		double ll, ll0 = hmm.loglik();
		
		for(int i=0; i<max_iter; i++) {
			hmm.train();
			
			if(Constants.plot()) hmmp.update();
			ll = hmm.loglik();
			myLogger.info("----------loglik "+ll);
			if(ll<ll0) {
				//throw new RuntimeException("Fatal error!!!");
				myLogger.info("LOGLIK DECREASED!!!");
				//break;
                ll0=ll;
                continue;
			}
			if( ll0!=Double.NEGATIVE_INFINITY && 
					Math.abs((ll-ll0)/ll0) < Constants.minImprov)
				break;
			ll0 = ll;
		}

		if(Constants.printPlots()){
			try {
				float width = hmmf.jframe.getSize().width,
						height = hmmf.jframe.getSize().height;
				Document document = new Document(new Rectangle(width, height));
				PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(plot_pdf));
				document.open();
				PdfContentByte canvas = writer.getDirectContent();
				PdfTemplate template = canvas.createTemplate(width, height);
				Graphics2D g2d = new PdfGraphics2D(template, width, height);
				hmmf.jframe.paint(g2d);
				g2d.dispose();
				canvas.addTemplate(template, 0, 0);
				document.close();
			} catch (FileNotFoundException | DocumentException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		String scaff_str = scaff[0]+
				(start_pos==null||start_pos[0]==Integer.MIN_VALUE?"":"_"+start_pos[0])+
				(end_pos==null||end_pos[0]==Integer.MAX_VALUE?"":"_"+end_pos[0]);
		for(int i=1; i<scaff.length; i++) {
			if(scaff_str.length()+scaff[i].length()+32<=Constants.MAX_FILE_ID_LENGTH)
				scaff_str += Constants.scaff_collapsed_str+scaff[i]+
				(start_pos==null||start_pos[i]==Integer.MIN_VALUE?"":"_"+start_pos[i])+
				(end_pos==null||end_pos[i]==Integer.MAX_VALUE?"":"_"+end_pos[i]);
			else {
				scaff_str += Constants.scaff_collapsed_str+"etc"+scaff.length;
				break;
			}
		}
		
		
		
		hmm.write(out_prefix, expr_id, scaff_str, resampling, loglik_diff);
	}
}
