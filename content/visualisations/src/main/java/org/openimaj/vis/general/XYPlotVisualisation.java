/**
 *
 */
package org.openimaj.vis.general;

import java.util.ArrayList;
import java.util.List;

import org.openimaj.image.MBFImage;
import org.openimaj.image.colour.RGBColour;
import org.openimaj.vis.VisualisationImpl;
import org.openimaj.vis.general.XYPlotVisualisation.LocatedObject;

/**
 * Abstract visualisation for plotting X,Y items. Uses the {@link AxesRenderer2D}
 * to determine the scale of the visualisation.
 *
 * @author David Dupplaw (dpd@ecs.soton.ac.uk)
 * @param <O> The type of object to be visualised
 * @created 3 Jun 2013
 */
public class XYPlotVisualisation<O> extends VisualisationImpl<List<LocatedObject<O>>>
{
	/**
	 * Class that locates an object.
	 *
	 * @param <O> The object type
	 *
	 * @author David Dupplaw (dpd@ecs.soton.ac.uk)
	 * @created 3 Jun 2013
	 */
	public static class LocatedObject<O>
	{
		/** The x position */
		public double x;

		/** The y position */
		public double y;

		/** The object */
		public O object;

		/**
		 * Create a located object
		 *
		 * @param x data point x location
		 * @param y data point y location
		 * @param object The object
		 */
		public LocatedObject( final double x, final double y, final O object )
		{
			this.x = x;
			this.y = y;
			this.object = object;
		}
	}

	/** */
	private static final long serialVersionUID = 1L;

	/** The renderer for the axes */
	protected final AxesRenderer2D<Float[], MBFImage> axesRenderer2D =
			new AxesRenderer2D<Float[], MBFImage>();

	/** Whether to render the axes on top of the data rather than underneath */
	private final boolean renderAxesLast = false;

	/** The item plotter to use */
	protected ItemPlotter<O, Float[], MBFImage> plotter;

	/** Whether to scale the axes to fit the data */
	private boolean autoScaleAxes = true;

	private boolean autoPositionXAxis = true;

	/**
	 * Default constructor
	 *
	 * @param plotter The item plotter to use
	 */
	public XYPlotVisualisation( final ItemPlotter<O, Float[], MBFImage> plotter )
	{
		this.plotter = plotter;
		this.init();
	}

	/**
	 * Constructor that provides the width and height of the visualisation.
	 *
	 * @param width Width of the vis in pixels
	 * @param height Height of the vis in pixels
	 * @param plotter The item plotter to use
	 */
	public XYPlotVisualisation( final int width, final int height, final ItemPlotter<O, Float[], MBFImage> plotter )
	{
		super( width, height );
		this.plotter = plotter;
		this.init();
	}

	/**
	 * Initialise
	 */
	private void init()
	{
		this.data = new ArrayList<LocatedObject<O>>();

		this.axesRenderer2D.setxAxisColour( RGBColour.WHITE );
		this.axesRenderer2D.setyAxisColour( RGBColour.WHITE );
		this.axesRenderer2D.setMajorTickColour( RGBColour.WHITE );
		this.axesRenderer2D.setMinorTickColour( RGBColour.GRAY );
		this.axesRenderer2D.setxTickLabelColour( RGBColour.GRAY );
		this.axesRenderer2D.setyTickLabelColour( RGBColour.GRAY );
		this.axesRenderer2D.setxAxisNameColour( RGBColour.WHITE );
		this.axesRenderer2D.setyAxisNameColour( RGBColour.WHITE );
		this.axesRenderer2D.setMajorGridColour( new Float[]{.5f,.5f,.5f,1f} );
		this.axesRenderer2D.setMinorGridColour( new Float[]{.5f,.5f,.5f,1f} );
		this.axesRenderer2D.setDrawMajorTickGrid( true );
		this.axesRenderer2D.setDrawMinorTickGrid( true );
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see org.openimaj.vis.VisualisationImpl#update()
	 */
	@Override
	public void update()
	{
		this.axesRenderer2D.setImage( this.visImage );
		if( this.autoPositionXAxis )
		{
			synchronized( this.axesRenderer2D )
			{
				// Note, this might not work very well, if the axes are rotated.
				final double xAxisPosition = this.axesRenderer2D.getAxisPaddingTop() +
					this.axesRenderer2D.getyAxisConfig().getMaxValue() *
					this.axesRenderer2D.getyAxisRenderer().getAxisLength() /
					(this.axesRenderer2D.getyAxisConfig().getMaxValue()
							- this.axesRenderer2D.getyAxisConfig().getMinValue());
				System.out.println( "Setting x position: "+xAxisPosition );
				this.axesRenderer2D.setxAxisPosition( xAxisPosition );
			}
		}

		synchronized( this.axesRenderer2D )
		{
			this.axesRenderer2D.precalc();
		}

		this.beforeAxesRender( this.visImage, this.axesRenderer2D );

		if( !this.renderAxesLast ) this.axesRenderer2D.renderAxis( this.visImage );

		// Tell the plotter we're about to start rendering items,
		// then loop over the items plotting them
		this.plotter.renderRestarting();
		synchronized( this.data )
		{
			for( final LocatedObject<O> o : this.data )
				this.plotter.plotObject( this.visImage, o, this.axesRenderer2D );
		}

		if( this.renderAxesLast ) this.axesRenderer2D.renderAxis( this.visImage );
	}

	/**
	 * A method that can be overridden to plot something prior to the axes being
	 * drawn.
	 *
	 * @param visImage The image to draw to
	 * @param renderer The axes renderer
	 */
	public void beforeAxesRender( final MBFImage visImage, final AxesRenderer2D<Float[], MBFImage> renderer )
	{
		// No implementation by default
	}

	/**
	 * Add an object to the plot
	 *
	 * @param x x location of data point
	 * @param y y location of data point
	 * @param object The object
	 */
	public void addPoint( final double x, final double y, final O object )
	{
		this.data.add( new LocatedObject<O>( x, y, object ) );
		this.validateData();
	}

	/**
	 * Remove a specific object
	 *
	 * @param object The object
	 */
	public void removePoint( final O object )
	{
		LocatedObject<O> toRemove = null;
		for( final LocatedObject<O> o : this.data )
		{
			if( o.object.equals( object ) )
			{
				toRemove = o;
				break;
			}
		}

		if( toRemove != null ) this.data.remove( toRemove );
		this.validateData();
	}

	/**
	 * Set the plotter
	 *
	 * @param plotter The plotter
	 */
	public void setItemPlotter( final ItemPlotter<O, Float[], MBFImage> plotter )
	{
		this.plotter = plotter;
	}

	/**
	 * Provides access to the underlying axes renderer so that various changes
	 * can be made to the visualisation.
	 *
	 * @return The axes renderer.
	 */
	public AxesRenderer2D<Float[], MBFImage> getAxesRenderer()
	{
		return this.axesRenderer2D;
	}

	/**
	 * Clear the data list.
	 */
	public void clearData()
	{
		synchronized( this.data )
		{
			this.data.clear();
		}
	}

	/**
	 *	{@inheritDoc}
	 * 	@see org.openimaj.vis.VisualisationImpl#setData(java.lang.Object)
	 */
	@Override
	public void setData( final List<LocatedObject<O>> data )
	{
		super.setData( data );
		this.validateData();
	}

	private void validateData()
	{
		if( this.autoScaleAxes && this.data.size() > 0 )
		{
			double minX = Double.MAX_VALUE;
			double maxX = Double.MIN_VALUE;
			double minY = Double.MAX_VALUE;
			double maxY = Double.MIN_VALUE;
			for( final LocatedObject<O> o : this.data)
			{
				minX = Math.min( minX, o.x );
				maxX = Math.max( maxX, o.x );
				minY = Math.min( minY, o.y );
				maxY = Math.max( maxY, o.y );
			}

			this.axesRenderer2D.setMaxXValue( maxX );
			this.axesRenderer2D.setMinXValue( minX );
			this.axesRenderer2D.setMaxYValue( maxY );
			this.axesRenderer2D.setMinYValue( minY );
			this.axesRenderer2D.precalc();

			System.out.println( "max x: "+maxX+", min x: "+minX+", max y: "+maxY+", min y: "+minY );
		}
	}

	/**
	 *	@return the autoScaleAxes
	 */
	public boolean isAutoScaleAxes()
	{
		return this.autoScaleAxes;
	}

	/**
	 *	@param autoScaleAxes the autoScaleAxes to set
	 */
	public void setAutoScaleAxes( final boolean autoScaleAxes )
	{
		this.autoScaleAxes = autoScaleAxes;
	}

	/**
	 *	@return the autoPositionXAxis
	 */
	public boolean isAutoPositionXAxis()
	{
		return this.autoPositionXAxis;
	}

	/**
	 *	@param autoPositionXAxis the autoPositionXAxis to set
	 */
	public void setAutoPositionXAxis( final boolean autoPositionXAxis )
	{
		this.autoPositionXAxis = autoPositionXAxis;
	}
}
