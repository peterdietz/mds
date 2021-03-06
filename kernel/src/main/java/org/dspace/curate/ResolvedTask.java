/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.curate;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dspace.content.DSpaceObject;
import org.dspace.core.Context;
import org.dspace.core.PluginManager;
import org.dspace.curate.record.Record;
import org.dspace.curate.record.Records;
import org.dspace.curate.record.Recorder;
import org.dspace.eperson.EPerson;

/**
 * ResolvedTask wraps an implementation of one of the CurationTask or
 * ScriptedTask interfaces and provides for uniform invocation based on
 * CurationTask methods.
 *
 * @author richardrodgers
 */
public class ResolvedTask
{
	private static Logger log = LoggerFactory.getLogger(ResolvedTask.class);
	
	private static final String recorderKey = Recorder.class.getName();
	
	// wrapped objects
	private CurationTask cTask;
	private ScriptedTask sTask;
	// local name of task
	private String taskName;
	// recorder (if any)
	private Recorder recorder = null;
	// annotation data
	private boolean distributive = false;
	private boolean mutative = false;
	private Curator.Invoked mode = null;
    private int[] codes = null;
    // record annotation metadata
    private List<RecordMetadata> recList = null;
	
	protected ResolvedTask(String taskName, CurationTask cTask)
	{
		this.taskName = taskName;
		this.cTask = cTask;
		// process annotations
		Class ctClass = cTask.getClass();
		distributive = ctClass.isAnnotationPresent(Distributive.class);
		mutative = ctClass.isAnnotationPresent(Mutative.class);
		Suspendable suspendAnno = (Suspendable)ctClass.getAnnotation(Suspendable.class);
        if (suspendAnno != null)
        {
            mode = suspendAnno.invoked();
            codes = suspendAnno.statusCodes();
        }
        Record recAnno = (Record)ctClass.getAnnotation(Record.class);
        if (recAnno != null)
        {
        	processRecord(recAnno);
        }
        // maybe multiple messages ?
        Records recsAnno = (Records)ctClass.getAnnotation(Records.class);
        if (recsAnno != null)
        {
        	for (Record rAnno : recsAnno.value())
        	{
        		processRecord(rAnno);
        	}
        }
	}
	
	protected ResolvedTask(String taskName, ScriptedTask sTask)
	{
		this.taskName = taskName;
		this.sTask = sTask;
		// annotation processing TBD
	}
	
    /**
     * Initialize task - parameters inform the task of it's invoking curator.
     * Since the curator can provide services to the task, this represents
     * curation DI.
     * 
     * @param curator the Curator controlling this task
     * @throws IOException
     */
    public void init(Curator curator) throws IOException
    {
    	// any recorder required?
    	if (recList != null)
    	{
    		recorder = (Recorder)curator.obtainResource(recorderKey);
    		if (recorder == null)
    		{
    			recorder = (Recorder)PluginManager.getSinglePlugin("curate", Recorder.class);
    			if (recorder != null)
    			{
    				recorder.init();
    				String policy = null;
    				if (recorder instanceof Closeable)
    				{
    					policy = "close";
    				}
    				curator.manageResource(recorderKey, recorder, policy);
    			}
    			else
    			{
    				log.error("No recorder configured");
    				throw new IOException("Missing Recorder");
    			}
    		}
    	}
    	if (unscripted())
    	{
    		cTask.init(curator, taskName);
    	}
    	else
    	{
    		sTask.init(curator, taskName);
    	}
    }

    /**
     * Perform the curation task upon passed DSO
     *
     * @param dso the DSpace object
     * @return status code
     * @throws IOException
     */
    public int perform(DSpaceObject dso) throws IOException
    {
    	return unscripted() ? cTask.perform(dso) : sTask.performDso(dso);
    }

    /**
     * Perform the curation task for passed id
     * 
     * @param ctx DSpace context object
     * @param id persistent ID for DSpace object
     * @return status code
     * @throws Exception
     */
    public int perform(Context ctx, String id) throws IOException
    {
    	return unscripted() ? cTask.perform(ctx, id) : sTask.performId(ctx, id);	
    }
    
    /**
     * Handle any record requests
     * 
     * @param objId identifier of DSpace Object
     * @param ctx a DSpace context
     * @param status the status code returned by the task
     * @param result the result string assigned by to task or null if absent
     */
    public void record(String objId, Context ctx, int status, String result) throws IOException
    {
    	if (recorder != null && recList != null)
    	{
    		String epId = null;
			if (ctx != null)
			{
				EPerson eperson = ctx.getCurrentUser();
				epId = (eperson != null) ? eperson.getName() : null;
			}
			long timestamp = System.currentTimeMillis();
    		for (RecordMetadata rmd : recList)
    		{
    			// is status code among those we respond to?
    			if (rmd.recCodes.contains(status))
    			{
    				recorder.record(timestamp, objId, epId, taskName,
    				        		rmd.recType, rmd.recValue, status, result);
    			}
    		}
    	}
    }
    
    /**
     * Returns local name of task
     * @return name
     *         the local name of the task
     */
    public String getName()
    {
    	return taskName;
    }
    
    /**
     * Returns whether task should be distributed through containers
     * @return boolean 
     *         true if the task is distributive
     */
    public boolean isDistributive()
    {
    	return distributive;
    }
    
    /**
     * Returns whether task alters (mutates) it's target objects
     * 
     */
    public boolean isMutative()
    {
    	return mutative;
    }
    
    public Curator.Invoked getMode()
    {
    	return mode;
    }
    
    public int[] getCodes()
    {
    	return codes;
    }
    
    private void processRecord(Record recAnno)
    {
    	if (recList == null)
    	{
    		recList = new ArrayList<RecordMetadata>();
    	}
    	recList.add(new RecordMetadata(recAnno));
    }
    
    private boolean unscripted()
    {
    	return sTask == null;
    }
    
    private class RecordMetadata
    {
    	public String recType;
    	public String recValue;
    	public List<Integer> recCodes;
    	
    	public RecordMetadata(Record rec)
    	{
    		recType = rec.type();
        	recValue = rec.value();
        	recCodes = new ArrayList<Integer>();
        	for (int code : rec.statusCodes())
        	{
        		recCodes.add(code);
        	}
    	}
    }
}
