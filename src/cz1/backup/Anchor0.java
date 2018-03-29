package cz1.backup;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;

import cz1.ngs.model.Blast6Segment;
import cz1.ngs.model.AlignmentSegment;
import cz1.ngs.model.Sequence;
import cz1.util.ArgsEngine;
import cz1.util.Executor;
import cz1.util.Utils;

public class Anchor0 extends Executor {
	
	@Override
	public void printUsage() {
		// TODO Auto-generated method stub
		myLogger.info(
				"\n\nUsage is as follows:\n"
						+ " -s/--subject            Subject/reference sequences file in FASTA format.\n"
						+ " -q/--query              Query sequences to anchor in FASTA format.\n"
						+ " -b/--blast              BLAST output (-outfmt 6) of query sequences against subject sequences.\n"
						+ " -i/--min-identity       Minimum identity between the query and subject sequences \n"
						+ "                         for an alignment record to consider (default 0.95).\n"
						+ " -f/--min-fraction       Minimum alignment fraction of the query sequence (default 0.5).\n"
						+ " -di/--diff-identity     Threshold of the difference of the identity between the primary and secondary \n"
						+ "                         alignments. If the difference is smaller than this value, the query \n"
						+ "                         sequence will be regarded as duplications. Otherwise, the secondary \n"
						+ "                         alignments will be discared (default 0.01).\n"
						+ " -df/--diff-fraction     Threshold of the difference of the alignment fraction between the primary and \n"
						+ "                         secondary alignments. If the difference is smaller than this value, the query \n"
						+ "                         sequence will be regarded as duplications. Otherwise, the secondary \n"
						+ "                         alignments will be discared (default 0.05).\n"
						+ " -o/--out-prefix         Prefix of the output FASTQ file.\n"
						+ "\n");	
	}

	private String subject_file = null;
	private String query_file = null;
	private String blast_out = null;
	private double min_ident = 95;
	private double min_frac = 0.50;
	private double diff_ident = 0.01;
	private double diff_frac = 0.05;
	private int min_overlap = 10;
	// maximum shift distance for two collinear alignment segments
	// 50% of the smaller segment size
	private double collinear_shift = 0.5;
	private String out_prefix = null;
	
	@Override
	public void setParameters(String[] args) {
		// TODO Auto-generated method stub
		if (myArgsEngine == null) {
			myArgsEngine = new ArgsEngine();
			myArgsEngine.add("-s", "--subject", true);
			myArgsEngine.add("-q", "--query", true);
			myArgsEngine.add("-b", "--blast", true);
			myArgsEngine.add("-i", "--min-identity", true);
			myArgsEngine.add("-f", "--min-fraction", true);
			myArgsEngine.add("-di", "--diff-identity", true);
			myArgsEngine.add("-df", "--diff-fraction", true);
			myArgsEngine.add("-o", "--out-prefix", true);
			myArgsEngine.parse(args);
		}
		
		if (myArgsEngine.getBoolean("-s")) {
			this.subject_file = myArgsEngine.getString("-s");
		} else {
			printUsage();
			throw new IllegalArgumentException("Please specify the subject/reference file.");
		}

		if (myArgsEngine.getBoolean("-q")) {
			this.query_file = myArgsEngine.getString("-q");
		} else {
			printUsage();
			throw new IllegalArgumentException("Please specify the query file.");
		}
		
		if (myArgsEngine.getBoolean("-b")) {
			this.blast_out = myArgsEngine.getString("-b");
		} else {
			printUsage();
			throw new IllegalArgumentException("Please specify the BLAST file.");
		}
		
		if (myArgsEngine.getBoolean("-o")) {
			this.out_prefix = myArgsEngine.getString("-o");
			if(new File(out_prefix+".fa").exists() || 
					new File(out_prefix+".map").exists() || 
					new File(out_prefix+"_unplaced.map").exists()) {
				throw new RuntimeException("Output files exist. Please specify a different name.");
			}
		} else {
			printUsage();
			throw new IllegalArgumentException("Please specify the prefix of output files.");
		}
		
		if (myArgsEngine.getBoolean("-i")) {
			this.min_ident = 100*Math.min(1.0, Double.parseDouble(myArgsEngine.getString("-i")));
		}
		
		if (myArgsEngine.getBoolean("-f")) {
			this.min_frac = Double.parseDouble(myArgsEngine.getString("-f"));
		}
		
		if (myArgsEngine.getBoolean("-di")) {
			this.diff_ident = Double.parseDouble(myArgsEngine.getString("-di"));
		}
		
		if (myArgsEngine.getBoolean("-df")) {
			this.diff_frac = Double.parseDouble(myArgsEngine.getString("-df"));
		}
	}

	private final static int max_clip = 30; // maximum clip of query alignment allowed
	private final static int gap_buff = 30; // buffer size for subject/reference sequences gap clips
	private final static int min_gap  = 10; // take this if estimated gap size is smaller than this
	private final static int max_gap  = 100; // take this if estimated gap size is larger than this
	
	@Override
	public void run() {
		// TODO Auto-generated method stub
		final Map<String, Sequence> sub_seqs = Sequence.parseFastaFileAsMap(subject_file);
		final Map<String, Sequence> qry_seqs = Sequence.parseFastaFileAsMap(query_file);
		
		// find 'N/n's in subject/reference sequences
		// which could have impact on parsing the blast records
		final Map<String, TreeRangeSet<Integer>> sub_gaps = new HashMap<String, TreeRangeSet<Integer>>();
		final Map<String, List<Blast6Segment>> anchored_records = new HashMap<String, List<Blast6Segment>>();
		
		for(Map.Entry<String, Sequence> entry : sub_seqs.entrySet()) {
			String seq_sn = entry.getKey();
			String seq_str = entry.getValue().seq_str();
			
			final TreeRangeSet<Integer> tmp_rangeSet = TreeRangeSet.create();
			for(int j=0; j<seq_str.length(); j++) {
				if(seq_str.charAt(j)=='N'||seq_str.charAt(j)=='n')
					// blast record is 1-based closed coordination
					tmp_rangeSet.add(Range.closed(j+1, j+1).
							canonical(DiscreteDomain.integers()));
			}
			int seq_ln = seq_str.length();
			final TreeRangeSet<Integer> range_set = TreeRangeSet.create();
			for(Range<Integer> range : tmp_rangeSet.asRanges()) {
				int lowerend = range.hasLowerBound() ? Math.max(0, range.lowerEndpoint()-gap_buff) : 0;
				int upperend = range.hasUpperBound() ? Math.min(seq_ln, range.upperEndpoint()+gap_buff-1) : seq_ln;
				range_set.add( Range.closed(lowerend, upperend).canonical(DiscreteDomain.integers()) );
			}
			sub_gaps.put(seq_sn, range_set);
			// initialise an array list for each reference chromosome
			anchored_records.put(seq_sn, new ArrayList<Blast6Segment>());
		}

		// parse blast records
		// blast records buffer
		final List<Blast6Segment> buff = new ArrayList<Blast6Segment>();
		// selected blast records
		final List<Blast6Segment> sel_recs = new ArrayList<Blast6Segment>();
		// temp list
		final List<Blast6Segment> tmp_records = new ArrayList<Blast6Segment>();
		// collinear merged record list
		// final List<Blast6Record> collinear_merged = new ArrayList<Blast6Record>();
		
		try {
			BufferedReader br_blast = Utils.getBufferedReader(blast_out);
			Blast6Segment tmp_record = Blast6Segment.blast6Segment(br_blast.readLine());
			Blast6Segment primary_record, secondary_record;
			String qry;
			double qry_ln, aln_frac;
			
			while(tmp_record!=null) {
				qry = tmp_record.qseqid();
				qry_ln = qry_seqs.get(qry).seq_ln();
				buff.clear();
				buff.add(tmp_record);
				while( (tmp_record=Blast6Segment.blast6Segment(br_blast.readLine()))!=null
						&&
						tmp_record.qseqid().equals(qry) ) {
					// filter by identity
					if(tmp_record.pident()>=this.min_ident)
						buff.add(tmp_record);
				}
				
				sel_recs.clear();
				// merge collinear records
				for(String sub : sub_seqs.keySet()) {
					// get all records for subject/reference sequence sub_seq
					tmp_records.clear();
					for(Blast6Segment record : buff)
						if(record.sseqid().equals(sub))
							tmp_records.add(record);
					
					if(tmp_records.isEmpty()) continue;
					
					// find alignment segments that can be deleted
					// those that are subsets of larger alignment segments
					// (Sstart, (sstart, send), Send) and (Qstart, (qstart, qend), Qend)
					Collections.sort(tmp_records, new Blast6Segment.SegmentSizeComparator());
					final Set<int[][]> ranges = new HashSet<int[][]>();
					outerloop:
						for(int i=0; i<tmp_records.size(); i++) {
							primary_record = tmp_records.get(i);
							int[][] range = new int[2][2];
							if(primary_record.sstart()<primary_record.send()) {
								range[0][0] = primary_record.sstart();
								range[0][1] = primary_record.send();
							} else {
								range[0][0] = primary_record.send();
								range[0][1] = primary_record.sstart();
							}
							if(primary_record.qstart()<primary_record.qend()) {
								range[1][0] = primary_record.qstart();
								range[1][1] = primary_record.qend();
							} else {
								range[1][0] = primary_record.qend();
								range[1][1] = primary_record.qstart();
							}
							for(int[][] r : ranges) {
								if(r[0][0]<=range[0][0]&&
										r[0][1]>=range[0][1]&&
										r[1][0]<=range[1][0]&&
										r[1][1]>=range[1][1]) {
									tmp_records.remove(i--);
									continue outerloop;
								}
							}
							ranges.add(range);
						}
					
					
					// TODO rewrite this part
					/***
					// find collinear alignment segments that can be merged
					Collections.sort(tmp_records, new BlastRecord.SInterceptComparator());
					
					collinear_merged.clear();
					final List<Blast6Record> temp = new ArrayList<Blast6Record>();
					for(int i=0; i<tmp_records.size(); ) {
						Blast6Record record = tmp_records.get(i);
						double max_shift;
						temp.clear();
						temp.add(record);
						
						// find collinear alignment segments
						outerloop:
							while( (++i)<tmp_records.size() ) {
								record = tmp_records.get(i);
								// check if is collinear with other alignment segments
								for(Blast6Record r : temp) {
									max_shift = collinear_shift*
											Math.min(r.length(), record.length());
									if(BlastRecord.sdistance(r, record)<=max_shift &&
											BlastRecord.qdistance(r, record)<=max_shift &&
											BlastRecord.pdistance(r, record)<=max_shift) {
										temp.add(record);
										continue outerloop;
									}
								}
								break;
							}
						
						// merge collinear alignment segments
						int qstart = Integer.MAX_VALUE;
						int qend = Integer.MIN_VALUE;
						int sstart = Integer.MAX_VALUE;
						int send = Integer.MIN_VALUE;
						double pident = 0;
						int length = 0;
						
						// qstart is always smaller than qend
						for(int j=0; j<temp.size(); j++) {
							record = temp.get(j);
							if(record.qstart()<qstart) {
								qstart = record.qstart();
								sstart = record.sstart();
							}
							if(record.qend()>qend) {
								qend = record.qend();
								send = record.send();
							}
							if(record.pident()>pident)
								pident = record.pident();
							length += record.length();
						}
						
						collinear_merged.add(new Blast6Record(qry,sub,pident,length,-1,-1,qstart,qend,sstart,send,-1,-1));
					}
					**/
					
					// find collinear alignment segments that can be merged
					// more accurate but slower
					Collections.sort(tmp_records, new AlignmentSegment.SubjectCoordinationComparator());
					
					Blast6Segment record;
					for(int i=0; i<tmp_records.size(); i++) {
						primary_record = tmp_records.get(i);
						for(int j=i+1; j<tmp_records.size(); j++) {
							secondary_record = tmp_records.get(j);
							double max_shift = collinear_shift*
									Math.min(primary_record.length(), secondary_record.length());
							if( (record=Blast6Segment.collinear(primary_record, secondary_record, max_shift))!=null ) {
								tmp_records.set(i, record);
								tmp_records.remove(j);
								--i;
								break;
							}
						}
					}
					
					// process blast records that clipped by gaps
					// (sstart, send)---(start2, send2)
					// (sstart  ...  ---  ...    send2)
					TreeRangeSet<Integer> sub_gap = sub_gaps.get(sub);
					Collections.sort(tmp_records, new AlignmentSegment.SubjectCoordinationComparator());
					
					for(int i=0; i<tmp_records.size(); i++) {
						primary_record = tmp_records.get(i);
						if( sub_gap.contains(primary_record.true_send()) ) {
							secondary_record = null;
							int sec_j = -1;
							for(int j=i+1; j<tmp_records.size(); j++) {
								if( tmp_records.get(j).true_sstart()>=
										primary_record.true_send() ) {
									secondary_record = tmp_records.get(j);
									sec_j = j;
									break;
								}
							}
							if(secondary_record==null || 
									AlignmentSegment.reverse(primary_record, secondary_record)) {
								// no clipping
								// reverse alignment segments
								continue;
							}
							
							if( sub_gap.contains(secondary_record.true_sstart()) &&
									sub_gap.rangeContaining(primary_record.true_send()).
									equals(sub_gap.rangeContaining(secondary_record.true_sstart()))) {
								// clipping
								// merge two alignment segments
								double pident = Math.max(primary_record.pident(), secondary_record.pident());
								int qstart = Math.min(primary_record.true_qstart(), secondary_record.true_qstart());
								int qend = Math.max(primary_record.true_qend(), secondary_record.true_qend());
								int sstart = Math.min(primary_record.true_sstart(), secondary_record.true_sstart());
								int send = Math.max(primary_record.true_send(), secondary_record.true_send());
								int length = qend-qstart+1;
								
								// replace primary record with merged record
								// delete secondary record
								Blast6Segment merged_record = primary_record.forward()?
										new Blast6Segment(qry,sub,pident,length,-1,-1,qstart,qend,sstart,send,-1,-1):
										new Blast6Segment(qry,sub,pident,length,-1,-1,qstart,qend,send,sstart,-1,-1);
								tmp_records.set(i, merged_record);
								tmp_records.remove(sec_j);
								
								// the merged records need to be processed
								--i;
							}
						}
					}
					
					// add to sel_recs
					sel_recs.addAll(tmp_records);
				}
				
				// filter by alignment fraction		
				buff.clear();
				buff.addAll(sel_recs);
				sel_recs.clear();
				for(Blast6Segment record : buff) {
					if(record.length()/qry_ln>=this.min_frac)
						sel_recs.add(record);
				}
				
				if(sel_recs.isEmpty()) {
					// unplaced query sequences
					// continue
					continue;
				}
				// filter blast records
				Collections.sort(sel_recs, new Blast6Segment.MatchIndentityComparator());
				// process primary alignment
				primary_record = sel_recs.get(0);
				anchored_records.get(primary_record.sseqid()).add(primary_record);
				aln_frac = primary_record.length()/qry_ln;
				// compare secondary alignments to primary alignment
				// and process
				for(int i=1; i<sel_recs.size(); i++) {
					secondary_record = sel_recs.get(i);
					if(secondary_record.pident()+this.diff_ident<primary_record.pident() ||
							secondary_record.length()/qry_ln+this.diff_frac<aln_frac) {
						break;
					} else {
						anchored_records.get(secondary_record.sseqid()).add(secondary_record);
					}
				}
			}
			br_blast.close();
		
			for(Map.Entry<String, List<Blast6Segment>> entry : anchored_records.entrySet()) {
				System.out.println(entry.getKey()+": "+entry.getValue().size());
			}
			
			final BufferedWriter bw_map = Utils.getBufferedWriter(out_prefix+".map");
			final BufferedWriter bw_fa = Utils.getBufferedWriter(out_prefix+".fa");
			final Set<String> anchored_seqs = new HashSet<String>();
			final List<String> sub_list = Sequence.parseSeqList(subject_file);
			
			for(String sub_sn : sub_list) {
				
				List<Blast6Segment> blast6_records = anchored_records.get(sub_sn);
				int total = blast6_records.size(), count = 0;
				
				// sort blast records
				Collections.sort(blast6_records, 
						new Blast6Segment.SubjectCoordinationComparator());
				// consensus
				int posUpto = 0, send_clip = 0;
				int sstart, send, qstart, qend, qlen, tmp_int, qstart_clip, qend_clip, gap_size;
				// distance to last 'N', start from next position to find longest common suffix-prefix
				int prev_n = Integer.MAX_VALUE;
				int mol_len = 0;
				String qseq;
				int nS, nQ;
				
				// will form a pseudomolecule
				StringBuilder seq_str = new StringBuilder();
				
				for(Blast6Segment record : blast6_records) {
					
					if(count++%10000==0) myLogger.info(sub_sn+" "+count+"/"+total+" done.");
					
					qlen = qry_seqs.get(record.qseqid()).seq_ln();
					sstart = record.sstart();
					send = record.send();
					qstart = record.qstart();
					qend = record.qend();
					if(sstart>send) {
						// make sure sstart<send
						tmp_int = sstart;
						sstart = send;
						send = tmp_int;
						tmp_int = qstart;
						qstart = qend;
						qend = tmp_int;
					}
					
					if(qstart>qend) {
						qstart_clip = qlen-qstart;
						qend_clip = qend-1;
						qseq = Sequence.revCompSeq(qry_seqs.get(record.qseqid()).seq_str());
					} else {
						qstart_clip = qstart-1;
						qend_clip = qlen-qend;
						qseq = qry_seqs.get(record.qseqid()).seq_str();
					}
					
					if(send<posUpto||sstart<posUpto&&qstart_clip>max_clip) {
						// skip if it is redundant
						//     ====================
						//      /-------\
						//        /----\
						// skip if contradiction observed
					    //     ====================
						//      /-------\
						//       --/----\--
						// TODO process
						continue;
					}
					
					// find longest suffix-prefix
					nS = seq_str.length();
					nQ = qseq.length();
					int nO = Math.min(prev_n, Math.min(nS, nQ));
					outerloop:
						for(; nO>=min_overlap; nO--) {
							int nS_i = nS-nO;
							for(int i=0; i<nO; i++) {
								if(seq_str.charAt(nS_i+i)!=qseq.charAt(i))
									continue outerloop;
							}
							break outerloop;
						}
					
					if(nO<min_overlap) {
						// no overlap found
						
						// simply extend
						//     ====================
						//      /-------\
						//               /----\

						// will insert a GAP anyway
						// if sstart<=posUpto will insert a small GAP min_gap
						// otherwise will insert a large GAP max(pseduo_distance, max_gap)
						if(posUpto>0) {
							if(sstart<=posUpto) {
								// if too much overlap, then treat it as a contradiction
								if(posUpto-sstart>min_overlap) {
									//discard
									continue;
								} else {
									// insert a min_gap
									gap_size = min_gap;
								}
							} else {
								// estimate gap size
								gap_size = (sstart-posUpto)-(send_clip+qstart_clip);
								if(gap_size<max_gap) gap_size = max_gap;	
							}
							
							bw_map.write("GAP\t"+
									gap_size+
									"\t0\t"+
									gap_size+
									"\t+\t"+
									sub_sn+
									"\t"+
									mol_len+
									"\t"+
									(mol_len+gap_size)+
									"\n");
							seq_str.append( Sequence.polyN(gap_size) );
							mol_len += gap_size;
						}
						bw_map.write(record.qseqid()+
								"\t"+
								qlen+
								"\t");
						if(qstart>qend) {
							// reverse
							bw_map.write("0\t"+qlen+"\t-\t");
						} else {
							// forward
							bw_map.write("0\t"+qlen+"\t+\t");
						}
						seq_str.append(qseq);
						bw_map.write(sub_sn+
								"\t"+
								mol_len+
								"\t"+
								(mol_len+qlen)+
								"\n");
						mol_len += qlen;
						prev_n = qlen;
						
						anchored_seqs.add(record.qseqid());
						
					} else {
						// overlap found
						// will not insert gap
						//     ====================
						//      /-------\
						//            /----\
						// calculate overlaps
						// process overlap
						
						qstart = nO;
						if(qstart==qlen) continue;
						bw_map.write(record.qseqid()+
								"\t"+
								(qlen-qstart)+
								"\t");
						
						if(qstart>qend) {
							// reverse
							bw_map.write( 0+
									"\t"+
									(qlen-qstart)+
									"\t-\t" );
						} else {
							// forward
							bw_map.write( qstart+
									"\t"+
									qlen+
									"\t+\t" );
						}
						bw_map.write(sub_sn+
								"\t"+
								mol_len+
								"\t"+
								(mol_len+qlen-qstart)+
								"\n");
						mol_len += qlen-qstart;
						prev_n += qlen-qstart;
						seq_str.append( qseq.substring(qstart) );

						anchored_seqs.add(record.qseqid());
					}
					
					posUpto = send;
					send_clip = qend_clip;
					
				} 
				
				if(seq_str.length()>0) 
					bw_fa.write(Sequence.formatOutput(sub_sn, seq_str.toString()));
			}

			bw_fa.close();
			bw_map.close();
			
			final BufferedWriter bw_ufa = Utils.getBufferedWriter(out_prefix+"_unplaced.fa");
			for(String seq : qry_seqs.keySet()) 
				if(!anchored_seqs.contains(seq))
					bw_ufa.write(qry_seqs.get(seq).formatOutput());
			bw_ufa.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}

