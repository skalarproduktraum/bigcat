package bdv.bigcat.viewer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.function.Consumer;

import bdv.viewer.ViewerPanel;

public class AtlasFocusHandler
{

	public static class OnEnterOnExit
	{

		private final Consumer< ViewerPanel > onEnter;

		private final Consumer< ViewerPanel > onExit;

		public OnEnterOnExit( final Consumer< ViewerPanel > onEnter, final Consumer< ViewerPanel > onExit )
		{
			super();
			this.onEnter = onEnter;
			this.onExit = onExit;
		}

	}

	private final HashSet< OnEnterOnExit > installOnExitRemovables;

	private final HashSet< OnEnterOnExit > installPermanent;

	private final HashMap< ViewerPanel, HashSet< OnEnterOnExit > > installed;

	public AtlasFocusHandler()
	{
		super();
		this.installOnExitRemovables = new HashSet<>();
		this.installPermanent = new HashSet<>();
		this.installed = new HashMap<>();
	}

	public synchronized void add( final OnEnterOnExit element, final boolean onExitRemovable )
	{
		if ( onExitRemovable )
			this.installOnExitRemovables.add( element );
		else
			this.installPermanent.add( element );
	}

	public synchronized void remove( final OnEnterOnExit element )
	{
		this.installOnExitRemovables.remove( element );
		this.installPermanent.remove( element );
	}

	public Consumer< ViewerPanel > onEnter()
	{
		return new OnEnter();
	}

	public Consumer< ViewerPanel > onExit()
	{
		return new OnExit();
	}

	private class OnEnter implements Consumer< ViewerPanel >
	{

		@Override
		public void accept( final ViewerPanel t )
		{
			synchronized ( AtlasFocusHandler.this )
			{
				installOnExitRemovables.forEach( consumer -> consumer.onEnter.accept( t ) );
				installPermanent.forEach( consumer -> {
					if ( !installed.containsKey( t ) )
						installed.put( t, new HashSet<>() );
					final HashSet< OnEnterOnExit > installedForViewer = installed.get( t );
					if ( !installedForViewer.contains( consumer ) )
					{
						consumer.onEnter.accept( t );
						installedForViewer.add( consumer );
					}
				} );
			}
		}
	}

	private class OnExit implements Consumer< ViewerPanel >
	{

		@Override
		public void accept( final ViewerPanel t )
		{
			System.out.println( "Handler: exiting" );
			synchronized ( AtlasFocusHandler.this )
			{
				installOnExitRemovables.forEach( consumer -> consumer.onExit.accept( t ) );
			}
		}
	}

}