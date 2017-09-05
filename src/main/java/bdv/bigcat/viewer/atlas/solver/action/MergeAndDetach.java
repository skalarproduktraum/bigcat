package bdv.bigcat.viewer.atlas.solver.action;

import java.util.Arrays;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class MergeAndDetach implements Action
{

	public static String IDENTIFIER = "merge-and-separate";

	private final long[] mergeIds;

	private final long[] from;

	public MergeAndDetach( final long[] mergeIds, final long[] from )
	{
		super();
		this.mergeIds = mergeIds;
		this.from = from;
	}

	public long[] mergeIds()
	{
		return mergeIds;
	}

	public long[] from()
	{
		return from;
	}

	@Override
	public String identifier()
	{
		return IDENTIFIER;
	}

	@Override
	public JsonElement jsonData()
	{
		final JsonObject json = new JsonObject();
		final JsonArray ids = new JsonArray();
		final JsonArray from = new JsonArray();
		Arrays.stream( mergeIds() ).forEach( ids::add );
		Arrays.stream( from() ).forEach( from::add );
		json.add( "fragments", ids );
		json.add( "from", from );
		return json;
	}

}