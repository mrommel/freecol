/**
 *  Copyright (C) 2002-2022   The FreeCol Team
 *
 *  This file is part of FreeCol.
 *
 *  FreeCol is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  FreeCol is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with FreeCol.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.sf.freecol.client.gui.mapviewer;

import static net.sf.freecol.common.util.StringUtils.lastPart;
import static net.sf.freecol.common.util.Utils.now;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.ActionListener;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D.Float;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.awt.image.VolatileImage;
import java.util.EnumMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.sf.freecol.client.ClientOptions;
import net.sf.freecol.client.FreeColClient;
import net.sf.freecol.client.control.FreeColClientHolder;
import net.sf.freecol.client.gui.Canvas;
import net.sf.freecol.client.gui.ImageLibrary;
import net.sf.freecol.client.gui.SwingGUI;
import net.sf.freecol.common.debug.FreeColDebugger;
import net.sf.freecol.common.i18n.Messages;
import net.sf.freecol.common.model.Ability;
import net.sf.freecol.common.model.BuildableType;
import net.sf.freecol.common.model.Colony;
import net.sf.freecol.common.model.Direction;
import net.sf.freecol.common.model.IndianSettlement;
import net.sf.freecol.common.model.Map;
import net.sf.freecol.common.model.PathNode;
import net.sf.freecol.common.model.Player;
import net.sf.freecol.common.model.Region;
import net.sf.freecol.common.model.Settlement;
import net.sf.freecol.common.model.Tile;
import net.sf.freecol.common.model.Turn;
import net.sf.freecol.common.model.Unit;
import net.sf.freecol.common.option.GameOptions;


/**
 * MapViewer is a private helper class of Canvas and SwingGUI.
 * 
 * This class is used by {@link CanvasMapViewer} for drawing the map on the {@link Canvas}.
 * 
 * The method {@link #displayMap(Graphics2D, Dimension)} renders the entire map, or just parts
 * of it depending on clip bounds and the dirty state controlled by
 * {@link MapViewerRepaintManager}.
 *    
 * Unit animations are still handled {@link UnitAnimator separately}, but will probably be moved
 * into {@code displayMap} in the future.
 * 
 * @see MapViewerBounds
 */
public final class MapViewer extends FreeColClientHolder {

    private static final Logger logger = Logger.getLogger(MapViewer.class.getName());

    private static enum BorderType { COUNTRY, REGION }

    /**
     * Calculates what part of the {@link Map} is visible on screen. This includes handling
     * the size, scaling and focus of the map.
     * 
     * Please note that when repainting the Map, it is only necessary to paint the
     * requested area given by {@link Graphics2D#getClipBounds}. Translating the
     * the clip bounds into tiles is performed by {@link TileClippingBounds}.
     */
    private final MapViewerBounds mapViewerBounds = new MapViewerBounds();
    
    /**
     * Holds state that is not part of the Game/Map state, but is still used when drawing
     * the map. For example the current active unit.
     */
    private final MapViewerState mapViewerState;
    
    /**
     * The internal scaled tile viewer to use.
     */
    private final TileViewer tv;
    
    /**
     * Holds buffers and determines the dirty state when drawing.
     */
    private final MapViewerRepaintManager rpm;
    
    /**
     * Utility functions that considers the current map size and scale. 
     */
    private final MapViewerScaledUtils mapViewerScaledUtils;
    
    /**
     * Scaled image library to use only for map operations.
     */
    private final ImageLibrary lib;
    
    /**
     * Bounds of the tiles to be rendered. These bounds are scaled according to the
     * zoom level of the map.
     */
    private TileBounds tileBounds = new TileBounds(new Dimension(0, 0), 1f);
    

    /**
     * The constructor to use.
     *
     * @param freeColClient The {@code FreeColClient} for the game.
     * @param lib An {@code ImageLibrary} to use for drawing to the map
     *     (and this is subject to the map scaling).
     * @param al An {@code ActionListener} for the cursor.
     */
    public MapViewer(FreeColClient freeColClient, ImageLibrary lib, ActionListener al) {
        super(freeColClient);
        
        this.lib = lib;
        this.tv = new TileViewer(freeColClient, lib);
        
        final ChatDisplay chatDisplay = new ChatDisplay(freeColClient);
        final UnitAnimator unitAnimator = new UnitAnimator(freeColClient, this, lib);
        this.mapViewerState = new MapViewerState(chatDisplay, unitAnimator, al);
        this.mapViewerScaledUtils = new MapViewerScaledUtils();
        this.rpm = new MapViewerRepaintManager();

        updateScaledVariables();
    }
    
    
    // Public API
    
    /**
     * Change the goto path.
     * 
     * @param gotoPath The new goto {@code PathNode}.
     * @return True if the goto path was changed.
     */
    public boolean changeGotoPath(PathNode gotoPath) {
        boolean result = mapViewerState.changeGotoPath(gotoPath);
        if (result) {
        	/*
        	 * TODO: Only mark changed tiles as dirty -- or even better: Always render
        	 *       gotoPath directly without storing it to any buffer.
        	 */
        	rpm.markAsDirty();
        }
        
        return result;
    }

    /**
     * Change the scale of the map.
     *
     * @param newScale The new map scale.
     */
    public void changeScale(float newScale) {
        this.lib.changeScaleFactor(newScale);
        this.tv.updateScaledVariables();
        updateScaledVariables();
        mapViewerBounds.positionMap();
        rpm.markAsDirty();
    }
    
    /**
     * Update the variables that depend on the image library scale.
     */
    private void updateScaledVariables() {
        // ATTENTION: we assume that all base tiles have the same size
        this.tileBounds = new TileBounds(lib.getTileSize(), lib.getScaleFactor());
        
        mapViewerBounds.updateSizeVariables(tileBounds);
        mapViewerScaledUtils.updateScaledVariables(lib);
    }

    /**
     * Change the displayed map size.
     *
     * @param size The new map size.
     */
    public void changeSize(Dimension size) {
        this.tileBounds = new TileBounds(this.lib.getTileSize(), this.lib.getScaleFactor());
        mapViewerBounds.changeSize(size, this.tileBounds);
        rpm.markAsDirty();
    }

    /**
     * Converts the given screen coordinates to Map coordinates.
     * It checks to see to which Tile the given pixel 'belongs'.
     *
     * @param x The x-coordinate in pixels.
     * @param y The y-coordinate in pixels.
     * @return The {@code Tile} that is located at the given position
     *     on the screen.
     */
    public Tile convertToMapTile(int x, int y) {
        return mapViewerBounds.convertToMapTile(getMap(), x, y);
    }
    
    /**
     * Displays the Map.
     *
     * @param g2d The {@code Graphics2D} object on which to draw the Map.
     * @return {@code true} if the entire map has been repainted.
     */
    @SuppressFBWarnings(value="NP_LOAD_OF_KNOWN_NULL_VALUE",
                        justification="lazy load of extra tiles")
    public boolean displayMap(Graphics2D g2d, Dimension size) {
        final long t0 = now();
        final Rectangle clipBounds = g2d.getClipBounds();
        
        if (mapViewerBounds.getFocus() == null) {
            paintBlackBackground(g2d, clipBounds);
            return false;
        }
        
        if (rpm.isRepaintsBlocked(size)) {
            final VolatileImage backBufferImage = rpm.getBackBufferImage();
            g2d.drawImage(backBufferImage, 0, 0, null);
            return false;
        }
        
        boolean fullMapRenderedWithoutUsingBackBuffer = rpm.prepareBuffers(mapViewerBounds, mapViewerBounds.getFocus());
        final Rectangle dirtyClipBounds = rpm.getDirtyClipBounds();
        if (rpm.isAllDirty()) {
            fullMapRenderedWithoutUsingBackBuffer = true;
        }
        
        final VolatileImage backBufferImage = rpm.getBackBufferImage();
        final BufferedImage nonAnimationBufferImage = rpm.getNonAnimationBufferImage();
        
        final Graphics2D backBufferG2d = backBufferImage.createGraphics();
        final AffineTransform backBufferOriginTransform = backBufferG2d.getTransform();

        final Map map = getMap();

        final Rectangle allRenderingClipBounds = clipBounds.union(dirtyClipBounds);
        paintBlackBackground(backBufferG2d, allRenderingClipBounds);
        
        // Display the animated base tiles:
        final TileClippingBounds animatedBaseTileTcb = new TileClippingBounds(map, allRenderingClipBounds);
        final long t1 = now();
        backBufferG2d.setClip(allRenderingClipBounds);
        backBufferG2d.translate(animatedBaseTileTcb.clipLeftX, animatedBaseTileTcb.clipTopY);
        paintEachTile(backBufferG2d, animatedBaseTileTcb, (tileG2d, tile) -> this.tv.displayAnimatedBaseTiles(tileG2d, tile));
               
        // Display everything else:
        final long t2 = now();
        if (!dirtyClipBounds.isEmpty()) {
            final TileClippingBounds tcb = new TileClippingBounds(map, dirtyClipBounds);
            final Graphics2D nonAnimationG2d = nonAnimationBufferImage.createGraphics();
            displayNonAnimationImages(nonAnimationG2d, dirtyClipBounds, tcb);
            nonAnimationG2d.dispose();
        }
        
        final long t3 = now();   
        backBufferG2d.setTransform(backBufferOriginTransform);
        backBufferG2d.setClip(allRenderingClipBounds);
        backBufferG2d.drawImage(nonAnimationBufferImage, 0, 0, null);
        backBufferG2d.dispose();

        g2d.drawImage(backBufferImage, 0, 0, null);      
        final long t4 = now();

        /*
         * Remove the check for "fullMapRenderedWithoutUsingBackBuffer" to get every repaint
         * logged: This includes several animations per second.
         */
        if (fullMapRenderedWithoutUsingBackBuffer && logger.isLoggable(Level.FINEST)) {
            final long gap = now() - t0;
            final StringBuilder sb = new StringBuilder(128);
            sb.append("displayMap fullRendering=").append(fullMapRenderedWithoutUsingBackBuffer)
                .append(" time= ").append(gap)
                .append(" init=").append(t1 - t0)
                .append(" animated=").append(t2 - t1)
                .append(" displayNonAnimationImages=").append(t3 - t2)
                .append(" finish=").append(t4 - t3)
                ;
            logger.finest(sb.toString());
        }
        
        rpm.markAsClean();
        
        return fullMapRenderedWithoutUsingBackBuffer;
    }


    private void displayNonAnimationImages(Graphics2D nonAnimationG2d,
            Rectangle clipBounds,
            TileClippingBounds tcb) {
        
        long t0 = now();
        final Player player = getMyPlayer(); // Check, can be null in map editor
        final ClientOptions options = getClientOptions();
        
        /* For settlement names and territorial borders 1 extra row needs
        to be drawn in north to prevent missing parts on partial redraws,
        as they can reach below their tiles, see BR#2580 */
        
        AffineTransform originTransform = nonAnimationG2d.getTransform();
        nonAnimationG2d.setComposite(AlphaComposite.Clear);
        nonAnimationG2d.fill(clipBounds);
        nonAnimationG2d.setComposite(AlphaComposite.SrcOver);
        nonAnimationG2d.setClip(clipBounds);
        nonAnimationG2d.translate(tcb.clipLeftX, tcb.clipTopY);
        
        long t1 = now();
        
        paintEachTile(nonAnimationG2d, tcb, (tileG2d, tile) -> this.tv.displayTileWithBeach(tileG2d, tile, true));
        
        nonAnimationG2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Display the borders
        long t2 = now();
        paintEachTile(nonAnimationG2d, tcb, (tileG2d, tile) -> this.tv.drawBaseTileTransitions(tileG2d, tile));

        // Draw the grid, if needed
        long t3 = now();
        displayGrid(nonAnimationG2d, options, tcb);
        
        // Paint full region borders
        long t4 = now();
        if (options.getInteger(ClientOptions.DISPLAY_TILE_TEXT) == ClientOptions.DISPLAY_TILE_TEXT_REGIONS) {
            paintEachTileWithExtendedImageSize(nonAnimationG2d, tcb, (tileG2d, tile) -> displayTerritorialBorders(tileG2d, tile, BorderType.REGION, true));
        }

        // Paint full country borders
        long t5 = now();
        if (options.getBoolean(ClientOptions.DISPLAY_BORDERS)) {
            paintEachTileWithExtendedImageSize(nonAnimationG2d, tcb, (tileG2d, tile) -> displayTerritorialBorders(tileG2d, tile, BorderType.COUNTRY, true));
        }

        // Apply fog of war to flat parts of all tiles
        long t6 = now();
        nonAnimationG2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                             RenderingHints.VALUE_ANTIALIAS_OFF);
        final RescaleOp fow;
        if (player != null && getSpecification().getBoolean(GameOptions.FOG_OF_WAR)) {
            // Knowing that we have FOW, prepare a rescaling for the
            // overlay step below.
            fow = new RescaleOp(new float[] { 0.8f, 0.8f, 0.8f, 1f },
                                new float[] { 0, 0, 0, 0 },
                                null);

            final Composite oldComposite = nonAnimationG2d.getComposite();
            nonAnimationG2d.setColor(Color.BLACK);
            nonAnimationG2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                                                        0.2f));
            
            paintEachTile(nonAnimationG2d, tcb, (tileG2d, tile) -> {
                if (!tile.isExplored() || player.canSee(tile)) {
                    return;
                }
                tileG2d.fill(mapViewerScaledUtils.getFog());
            });
            nonAnimationG2d.setComposite(oldComposite);
        } else {
            fow = null;
        }
        
        // Display unknown tile borders:
        long t7 = now();
        paintEachTile(nonAnimationG2d, tcb, (tileG2d, tile) -> this.tv.displayUnknownTileBorder(tileG2d, tile));

        // Display the Tile overlays
        long t8 = now();
        final int colonyLabels = options.getInteger(ClientOptions.COLONY_LABELS);
        boolean withNumbers = (colonyLabels == ClientOptions.COLONY_LABELS_CLASSIC);
        paintEachTile(nonAnimationG2d, tcb, (tileG2d, tile) -> {
            if (!tile.isExplored()) {
                return;
            }
            BufferedImage overlayImage = this.lib.getScaledOverlayImage(tile);
            RescaleOp rop = (player == null || player.canSee(tile)) ? null : fow;
            this.tv.displayTileItems(tileG2d, tile, rop, overlayImage);
            this.tv.displaySettlementWithChipsOrPopulationNumber(tileG2d, tile,
                withNumbers, rop);
            this.tv.displayOptionalTileText(tileG2d, tile);
        });
        
        // Paint transparent region borders
        long t9 = now();
        nonAnimationG2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                             RenderingHints.VALUE_ANTIALIAS_ON);
        if (options.getInteger(ClientOptions.DISPLAY_TILE_TEXT) == ClientOptions.DISPLAY_TILE_TEXT_REGIONS) {
            paintEachTileWithExtendedImageSize(nonAnimationG2d, tcb, (tileG2d, tile) -> displayTerritorialBorders(tileG2d, tile, BorderType.REGION, false));
        }

        // Paint transparent country borders
        long t10 = now();
        if (options.getBoolean(ClientOptions.DISPLAY_BORDERS)) {
            paintEachTileWithExtendedImageSize(nonAnimationG2d, tcb, (tileG2d, tile) -> displayTerritorialBorders(tileG2d, tile, BorderType.COUNTRY, false));
        }

        // Display cursor for selected tile or active unit
        long t11 = now();
        final Tile cursorTile = getVisibleCursorTile();
        if (cursorTile != null && mapViewerState.getCursor().isActive() && !mapViewerState.getUnitAnimator().isUnitsOutForAnimation()) {
            /*
             * The cursor is hidden when units are animated. 
             */
            
            paintSingleTile(nonAnimationG2d, tcb, cursorTile, (tileG2d, tile) -> {
                nonAnimationG2d.drawImage(this.lib.getScaledImage(ImageLibrary.UNIT_SELECT), 0, 0, null);
            });
        }

        // Display units
        long t12 = now();
        nonAnimationG2d.setColor(Color.BLACK);
        final boolean revengeMode = getGame().isInRevengeMode();
        if (!revengeMode) {
            paintEachTile(nonAnimationG2d, tcb, (tileG2d, tile) -> {
                final Unit unit = mapViewerState.findUnitInFront(tile);
                if (unit == null || mapViewerState.getUnitAnimator().isOutForAnimation(unit)) {
                    return;
                }
                displayUnit(tileG2d, unit);
            });
        } else {
            final BufferedImage darkness = this.lib.getScaledImage(ImageLibrary.DARKNESS);
            paintEachTileWithSuperExtendedImageSize(nonAnimationG2d, tcb, (tileG2d, tile) -> {
                final Unit unit = mapViewerState.findUnitInFront(tile);
                if (unit == null || mapViewerState.getUnitAnimator().isOutForAnimation(unit)) {
                    return;
                }
                
                if (unit.isUndead()) {
                    this.tv.displayCenteredImage(tileG2d, darkness);
                }
                displayUnit(tileG2d, unit);

            });
        }
        
        long t13 = now();
        paintEachTileWithExtendedImageSize(nonAnimationG2d, tcb, (tileG2d, tile) -> {
            if (!tile.isExplored()) {
                return;
            }
            BufferedImage overlayImage = this.lib.getScaledAboveTileImage(tile);
            if (overlayImage != null) {
            	tileG2d.drawImage(overlayImage,
            			(tileBounds.getWidth() - overlayImage.getWidth()) / 2,
            			(tileBounds.getHeight() - overlayImage.getHeight()) / 2,
            			null);
            }
        });

        // Display the colony names, if needed
        long t14 = now();
        if (colonyLabels != ClientOptions.COLONY_LABELS_NONE) {
            paintEachTileWithExtendedImageSize(nonAnimationG2d, tcb, (tileG2d, tile) -> {
                final Settlement settlement = tile.getSettlement();
                if (settlement == null) {
                    return;
                }
                final RescaleOp rop = (player == null || player.canSee(tile)) ? null : fow;
                displaySettlementLabels(tileG2d, settlement, player, colonyLabels, rop);
            });
        }

        // Display goto path
        long t15 = now();
        nonAnimationG2d.setTransform(originTransform);
        if (mapViewerState.getUnitPath() != null) {
            displayPath(nonAnimationG2d, mapViewerState.getUnitPath());
        } else if (mapViewerState.getGotoPath() != null) {
            displayPath(nonAnimationG2d, mapViewerState.getGotoPath());
        }

        // Draw the chat
        mapViewerState.getChatDisplay().display(nonAnimationG2d, mapViewerBounds.getSize());
        
        long t16 = now();
        
        if (logger.isLoggable(Level.FINEST)) {
            final long gap = now() - t0;
            final double avg = ((double)gap)
                    / ((tcb.getBottomRightDirtyTile().getX()-tcb.getTopLeftDirtyTile().getX()) * (tcb.getBottomRightDirtyTile().getY()-tcb.getBottomRightDirtyTile().getY()));
            final StringBuilder sb = new StringBuilder(128);
            sb.append("displayNonAnimationImages time = ").append(gap)
            .append(" for ").append(tcb.getTopLeftDirtyTile())
            .append(" to ").append(tcb.getBottomRightDirtyTile())
                .append(" average ").append(avg)
                .append(" t1=").append(t1 - t0)
                .append(" t2=").append(t2 - t1)
                .append(" t3=").append(t3 - t2)
                .append(" t4=").append(t4 - t3)
                .append(" t5=").append(t5 - t4)
                .append(" t6=").append(t6 - t5)
                .append(" t7=").append(t7 - t6)
                .append(" t8=").append(t8 - t7)
                .append(" t9=").append(t9 - t8)
                .append(" t10=").append(t10 - t9)
                .append(" t11=").append(t11 - t10)
                .append(" t12=").append(t12 - t11)
                .append(" t13=").append(t13 - t12)
                .append(" t14=").append(t14 - t13)
                .append(" t15=").append(t15 - t14)
                .append(" t16=").append(t16 - t15)
                ;
            logger.finest(sb.toString());
        }
    }

    private void displayGrid(Graphics2D g2d, final ClientOptions options, final TileClippingBounds tcb) {
        if (options.getBoolean(ClientOptions.DISPLAY_GRID)) {
            // Generate a zigzag GeneralPath
            final AffineTransform baseTransform = g2d.getTransform();
            GeneralPath gridPath = new GeneralPath();
            gridPath.moveTo(0, 0);
            int nextX = tileBounds.getHalfWidth();
            int nextY = -tileBounds.getHalfHeight();
            for (int i = 0; i <= ((tcb.getBottomRightDirtyTile().getX() - tcb.getTopLeftDirtyTile().getX()) * 2 + 1); i++) {
                gridPath.lineTo(nextX, nextY);
                nextX += tileBounds.getHalfWidth();
                nextY = (nextY == 0) ? -tileBounds.getHalfHeight() : 0;
            }

            // Display the grid
            g2d.setStroke(mapViewerScaledUtils.getGridStroke());
            g2d.setColor(Color.BLACK);
            for (int row = tcb.getTopLeftDirtyTile().getY(); row <= tcb.getBottomRightDirtyTile().getY(); row++) {
                g2d.translate(0, tileBounds.getHalfHeight());
                AffineTransform rowTransform = g2d.getTransform();
                if ((row & 1) == 1) {
                    g2d.translate(tileBounds.getHalfWidth(), 0);
                }
                g2d.draw(gridPath);
                g2d.setTransform(rowTransform);
            }
            g2d.setTransform(baseTransform);
        }
    }    
    
    private void paintBlackBackground(Graphics2D g2d, final Rectangle rectangle) {
        g2d.setColor(Color.black);
        g2d.fillRect(rectangle.x, rectangle.y, rectangle.width, rectangle.height);
    }

    private void displaySettlementLabels(Graphics2D g2d, Settlement settlement,
                                         Player player, int colonyLabels,
                                         RescaleOp rop) {
        if (settlement.isDisposed()) {
            logger.warning("Settlement display race detected: "
                           + settlement.getName());
            return;
        }
        String name = Messages.message(settlement.getLocationLabelFor(player));
        if (name == null) return;

        Color backgroundColor = settlement.getOwner().getNationColor();
        if (backgroundColor == null) backgroundColor = Color.WHITE;
        // int yOffset = this.lib.getSettlementImage(settlement).getHeight() + 1;
        int yOffset = tileBounds.getHeight();
        switch (colonyLabels) {
        case ClientOptions.COLONY_LABELS_CLASSIC:
            BufferedImage img = this.lib.getStringImage(g2d, name, backgroundColor,
                    mapViewerScaledUtils.getFontNormal());
            g2d.drawImage(img, rop, (tileBounds.getWidth() - img.getWidth())/2 + 1,
                          yOffset);
            break;

        case ClientOptions.COLONY_LABELS_MODERN:
        default:
            backgroundColor = new Color(backgroundColor.getRed(),
                                        backgroundColor.getGreen(),
                                        backgroundColor.getBlue(), 128);
            TextSpecification[] specs = new TextSpecification[1];
            if (settlement instanceof Colony
                && settlement.getOwner() == player) {
                Colony colony = (Colony) settlement;
                BuildableType buildable = colony.getCurrentlyBuilding();
                if (buildable != null && mapViewerScaledUtils.getFontProduction() != null) {
                    specs = new TextSpecification[2];
                    String t = Messages.getName(buildable) + " " +
                        Turn.getTurnsText(colony.getTurnsToComplete(buildable));
                    specs[1] = new TextSpecification(t, mapViewerScaledUtils.getFontProduction());
                }
            }
            specs[0] = new TextSpecification(name, mapViewerScaledUtils.getFontNormal());

            BufferedImage nameImage = createLabel(g2d, specs, backgroundColor);
            int spacing = 3;
            BufferedImage leftImage = null;
            BufferedImage rightImage = null;
            if (settlement instanceof Colony) {
                Colony colony = (Colony)settlement;
                String string = Integer.toString(colony.getApparentUnitCount());
                leftImage = createLabel(g2d, string,
                    ((colony.getPreferredSizeChange() > 0)
                        ? mapViewerScaledUtils.getFontItalic() : mapViewerScaledUtils.getFontNormal()),
                    backgroundColor);
                if (player.owns(settlement)) {
                    int bonusProduction = colony.getProductionBonus();
                    if (bonusProduction != 0) {
                        String bonus = (bonusProduction > 0)
                            ? "+" + bonusProduction
                            : Integer.toString(bonusProduction);
                        rightImage = createLabel(g2d, bonus, mapViewerScaledUtils.getFontNormal(),
                                                 backgroundColor);
                    }
                }
            } else if (settlement instanceof IndianSettlement) {
                IndianSettlement is = (IndianSettlement) settlement;
                if (is.getType().isCapital()) {
                    leftImage = createCapitalLabel(nameImage.getHeight(),
                        5, backgroundColor);
                }
                
                Unit missionary = is.getMissionary();
                if (missionary != null) {
                    boolean expert = missionary.hasAbility(Ability.EXPERT_MISSIONARY);
                    backgroundColor = missionary.getOwner().getNationColor();
                    backgroundColor = new Color(backgroundColor.getRed(),
                                                backgroundColor.getGreen(),
                                                backgroundColor.getBlue(), 128);
                    rightImage = createReligiousMissionLabel(nameImage.getHeight(), 5,
                                                             backgroundColor, expert);
                }
            }
            
            int width = this.lib.scaleInt(nameImage.getWidth()
                + ((leftImage == null) ? 0 : leftImage.getWidth() + spacing)
                + ((rightImage == null) ? 0 : rightImage.getWidth() + spacing));
            int xOffset = (tileBounds.getWidth() - width)/2;
            yOffset -= this.lib.scaleInt(nameImage.getHeight())/2;
            if (leftImage != null) {
                g2d.drawImage(leftImage, rop, xOffset, yOffset);
                xOffset += this.lib.scaleInt(leftImage.getWidth() + spacing);
            }
            g2d.drawImage(nameImage, rop, xOffset, yOffset);
            if (rightImage != null) {
                xOffset += this.lib.scaleInt(nameImage.getWidth() + spacing);
                g2d.drawImage(rightImage, rop, xOffset, yOffset);
            }
            break;
        }
    }

    /**
     * Draws the pentagram indicating a native capital.
     *
     * @param extent The nominal height of the image.
     * @param padding Padding to add around the image.
     * @param backgroundColor The image background color.
     * @return A suitable {@code BufferedImage}.
     */
    private static BufferedImage createCapitalLabel(int extent, int padding,
                                                    Color backgroundColor) {
        // create path
        double deg2rad = Math.PI/180.0;
        double angle = -90.0 * deg2rad;
        double offset = extent * 0.5;
        double size1 = (extent - padding - padding) * 0.5;

        GeneralPath path = new GeneralPath();
        path.moveTo(Math.cos(angle) * size1 + offset, Math.sin(angle) * size1 + offset);
        for (int i = 0; i < 4; i++) {
            angle += 144 * deg2rad;
            path.lineTo(Math.cos(angle) * size1 + offset, Math.sin(angle) * size1 + offset);
        }
        path.closePath();

        // draw everything
        BufferedImage bi = new BufferedImage(extent, extent, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = bi.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                             RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                             RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(backgroundColor);
        g2d.fill(new RoundRectangle2D.Float(0, 0, extent, extent, padding, padding));
        g2d.setColor(Color.BLACK);
        g2d.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.draw(path);
        g2d.setColor(Color.WHITE);
        g2d.fill(path);
        g2d.dispose();
        return bi;
    }

    /**
     * Creates an BufferedImage that shows the given text centred on a
     * translucent rounded rectangle with the given color.
     *
     * @param g2d a {@code Graphics2D}
     * @param text a {@code String}
     * @param font a {@code Font}
     * @param backgroundColor a {@code Color}
     * @return an {@code BufferedImage}
     */
    private static BufferedImage createLabel(Graphics2D g2d, String text,
                                             Font font, Color backgroundColor) {
        TextSpecification[] specs = new TextSpecification[1];
        specs[0] = new TextSpecification(text, font);
        return createLabel(g2d, specs, backgroundColor);
    }

    /**
     * Creates an BufferedImage that shows the given text centred on a
     * translucent rounded rectangle with the given color.
     *
     * @param g2d a {@code Graphics2D}
     * @param textSpecs a {@code TextSpecification} array
     * @param backgroundColor a {@code Color}
     * @return a {@code BufferedImage}
     */
    private static BufferedImage createLabel(Graphics2D g2d,
                                             TextSpecification[] textSpecs,
                                             Color backgroundColor) {
        int hPadding = 15;
        int vPadding = 10;
        int linePadding = 5;
        int width = 0;
        int height = vPadding;
        int i;

        TextSpecification spec;
        TextLayout[] labels = new TextLayout[textSpecs.length];
        TextLayout label;

        for (i = 0; i < textSpecs.length; i++) {
            spec = textSpecs[i];
            label = new TextLayout(spec.text, spec.font,
                                   g2d.getFontRenderContext());
            labels[i] = label;
            Rectangle textRectangle = label.getPixelBounds(null, 0, 0);
            width = Math.max(width, textRectangle.width + hPadding);
            if (i > 0) height += linePadding;
            height += (int) (label.getAscent() + label.getDescent());
        }

        int radius = Math.min(hPadding, vPadding);

        BufferedImage bi = new BufferedImage(width, height,
                                             BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = bi.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                            RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                            RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2.setColor(backgroundColor);
        g2.fill(new RoundRectangle2D.Float(0, 0, width, height, radius, radius));
        g2.setColor(ImageLibrary.makeForegroundColor(backgroundColor));
        float y = vPadding / 2.0f;
        for (i = 0; i < labels.length; i++) {
            Rectangle textRectangle = labels[i].getPixelBounds(null, 0, 0);
            float x = (width - textRectangle.width) / 2.0f;
            y += labels[i].getAscent();
            labels[i].draw(g2, x, y);
            y += labels[i].getDescent() + linePadding;
        }
        g2.dispose();
        return bi;
    }

    /**
     * Draws a cross indicating a religious mission is present in the
     * native village.
     *
     * @param extent The nominal height of the image.
     * @param padding Padding to add around the image.
     * @param backgroundColor The image background color.
     * @param expertMissionary True if the label should show expertise.
     * @return A suitable {@code BufferedImage}.
     */
    private static BufferedImage createReligiousMissionLabel(int extent,
            int padding, Color backgroundColor, boolean expertMissionary) {
        // create path
        double offset = extent * 0.5;
        double size1 = extent - padding - padding;
        double bar = size1 / 3.0;
        double inset = 0.0;
        double kludge = 0.0;

        GeneralPath circle = new GeneralPath();
        GeneralPath cross = new GeneralPath();
        if (expertMissionary) {
            // this is meant to represent the eucharist (the -1, +1 thing is a nasty kludge)
            circle.append(new Ellipse2D.Double(padding-1, padding-1, size1+1, size1+1), false);
            inset = 4.0;
            bar = (size1 - inset - inset) / 3.0;
            // more nasty -1, +1 kludges
            kludge = 1.0;
        }
        offset -= 1.0;
        cross.moveTo(offset, padding + inset - kludge);
        cross.lineTo(offset, extent - padding - inset);
        cross.moveTo(offset - bar, padding + bar + inset);
        cross.lineTo(offset + bar + 1, padding + bar + inset);

        // draw everything
        BufferedImage bi = new BufferedImage(extent, extent, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = bi.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(backgroundColor);
        g2d.fill(new RoundRectangle2D.Float(0, 0, extent, extent, padding, padding));
        g2d.setColor(ImageLibrary.makeForegroundColor(backgroundColor));
        if (expertMissionary) {
            g2d.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2d.draw(circle);
            g2d.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        } else {
            g2d.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        }
        g2d.draw(cross);
        g2d.dispose();
        return bi;
    }


    /**
     * Display a path.
     *
     * @param g2d The {@code Graphics2D} to display on.
     * @param path The {@code PathNode} to display.
     */
    private void displayPath(Graphics2D g2d, PathNode path) {
        final boolean debug = FreeColDebugger
            .isInDebugMode(FreeColDebugger.DebugMode.PATHS);

        for (PathNode p = path; p != null; p = p.next) {
            Tile tile = p.getTile();
            if (tile == null) continue;
            Point point = mapViewerBounds.calculateTilePosition(tile, false);
            if (point == null) continue;

            BufferedImage image = (p.isOnCarrier())
                ? ImageLibrary.getPathImage(ImageLibrary.PathType.NAVAL)
                : (mapViewerState.getActiveUnit() != null)
                ? ImageLibrary.getPathImage(mapViewerState.getActiveUnit())
                : null;

            BufferedImage turns = null;
            if (mapViewerScaledUtils.getFontTiny() != null) {
                if (debug) { // More detailed display
                    if (mapViewerState.getActiveUnit() != null) {
                        image = ImageLibrary.getPathNextTurnImage(mapViewerState.getActiveUnit());
                    }
                    turns = this.lib.getStringImage(g2d,
                        Integer.toString(p.getTurns())
                        + "/" + Integer.toString(p.getMovesLeft()),
                        Color.WHITE, mapViewerScaledUtils.getFontTiny());
                } else {
                    turns = (p.getTurns() <= 0) ? null
                        : this.lib.getStringImage(g2d,
                            Integer.toString(p.getTurns()),
                            Color.WHITE, mapViewerScaledUtils.getFontTiny());
                }
                g2d.setColor((turns == null) ? Color.GREEN : Color.RED);
            }

            g2d.translate(point.x, point.y);
            if (image == null) {
                g2d.fillOval(tileBounds.getHalfWidth(), tileBounds.getHalfHeight(), 10, 10);
                g2d.setColor(Color.BLACK);
                g2d.drawOval(tileBounds.getHalfWidth(), tileBounds.getHalfHeight(), 10, 10);
            } else {
                this.tv.displayCenteredImage(g2d, image);
                if (turns != null) {
                    this.tv.displayCenteredImage(g2d, turns);
                }
            }
            g2d.translate(-point.x, -point.y);
        }
    }

    /**
     * Displays the given Unit onto the given Graphics2D object at the
     * location specified by the coordinates.
     *
     * @param g2d The Graphics2D object on which to draw the Unit.
     * @param unit The Unit to draw.
     */
    private void displayUnit(Graphics2D g2d, Unit unit) {
        final Player player = getMyPlayer();

        // Draw the unit.
        // If unit is sentry, draw in grayscale
        boolean fade = (unit.getState() == Unit.UnitState.SENTRY)
            || (unit.hasTile()
                && player != null && !player.canSee(unit.getTile()));
        BufferedImage image = this.lib.getScaledUnitImage(unit, fade);
        Point p = calculateUnitImagePositionInTile(image);
        g2d.drawImage(image, p.x, p.y, null);

        // Draw an occupation and nation indicator.
        String text = Messages.message(unit.getOccupationLabel(player, false));
        g2d.drawImage(this.lib.getOccupationIndicatorChip(g2d, unit, text),
                      this.lib.scaleInt(TileViewer.STATE_OFFSET_X), 0, null);

        // Draw one small line for each additional unit (like in civ3).
        int unitsOnTile = 0;
        if (unit.hasTile()) {
            // When a unit is moving from tile to tile, it is
            // removed from the source tile.  So the unit stack
            // indicator cannot be drawn during the movement see
            // UnitMoveAnimation.animate() for details
            unitsOnTile = unit.getTile().getTotalUnitCount();
        }
        if (unitsOnTile > 1) {
            g2d.setColor(Color.WHITE);
            int unitLinesY = TileBounds.OTHER_UNITS_OFFSET_Y;
            int x1 = this.lib.scaleInt(TileViewer.STATE_OFFSET_X
                + TileBounds.OTHER_UNITS_OFFSET_X);
            int x2 = this.lib.scaleInt(TileViewer.STATE_OFFSET_X
                + TileBounds.OTHER_UNITS_OFFSET_X + TileBounds.OTHER_UNITS_WIDTH);
            for (int i = 0; i < unitsOnTile && i < TileBounds.MAX_OTHER_UNITS; i++) {
                g2d.drawLine(x1, unitLinesY, x2, unitLinesY);
                unitLinesY += 2;
            }
        }

        // FOR DEBUGGING
        net.sf.freecol.server.ai.AIUnit au;
        if (FreeColDebugger.isInDebugMode(FreeColDebugger.DebugMode.MENUS)
            && player != null && !player.owns(unit)
            && unit.getOwner().isAI()
            && getFreeColServer() != null
            && getFreeColServer().getAIMain() != null
            && (au = getFreeColServer().getAIMain().getAIUnit(unit)) != null) {
            if (FreeColDebugger.debugShowMission()) {
                g2d.setColor(Color.WHITE);
                g2d.drawString((!au.hasMission()) ? "No mission"
                    : lastPart(au.getMission().getClass().toString(), "."),
                    0, 0);
            }
            if (FreeColDebugger.debugShowMissionInfo() && au.hasMission()) {
                g2d.setColor(Color.WHITE);
                g2d.drawString(au.getMission().toString(), 0, 25);
            }
        }
    }

    /**
     * Gets the coordinates to draw a unit in a given tile.
     *
     * @param unitImage The unit's image
     * @return The coordinates where the unit should be drawn on screen
     */
    private Point calculateUnitImagePositionInTile(BufferedImage unitImage) {
        int unitX = (tileBounds.getWidth() - unitImage.getWidth()) / 2;
        int unitY = (tileBounds.getHeight() - unitImage.getHeight()) / 2
            - this.lib.scaleInt(TileBounds.UNIT_OFFSET);
        return new Point(unitX, unitY);
    }

    /**
     * Draws the borders of a territory on the given Tile. The
     * territory is either a country or a region.
     *
     * @param g2d a {@code Graphics2D}
     * @param tile a {@code Tile}
     * @param type a {@code BorderType}
     * @param opaque a {@code boolean}
     */
    private void displayTerritorialBorders(Graphics2D g2d, Tile tile,
                                           BorderType type, boolean opaque) {
        Player owner = tile.getOwner();
        Region region = tile.getRegion();
        if ((type == BorderType.COUNTRY && owner != null)
            || (type == BorderType.REGION && region != null)) {
            Stroke oldStroke = g2d.getStroke();
            g2d.setStroke(mapViewerScaledUtils.getBorderStroke());
            Color oldColor = g2d.getColor();
            Color c = null;
            if (type == BorderType.COUNTRY)
                c = owner.getNationColor();
            if (c == null)
                c = Color.WHITE;
            Color newColor = new Color(c.getRed(), c.getGreen(), c.getBlue(),
                                 (opaque) ? 255 : 100);
            g2d.setColor(newColor);
            GeneralPath path = new GeneralPath(GeneralPath.WIND_EVEN_ODD);
            final EnumMap<Direction, Float> borderPoints = mapViewerScaledUtils.getBorderPoints();
            EnumMap<Direction, Float> controlPoints = mapViewerScaledUtils.getControlPoints();
            
            path.moveTo(borderPoints.get(Direction.longSides.get(0)).x,
                    borderPoints.get(Direction.longSides.get(0)).y);
            for (Direction d : Direction.longSides) {
                Tile otherTile = tile.getNeighbourOrNull(d);
                Direction next = d.getNextDirection();
                Direction next2 = next.getNextDirection();
                if (otherTile == null
                    || (type == BorderType.COUNTRY && !owner.owns(otherTile))
                    || (type == BorderType.REGION && otherTile.getRegion() != region)) {
                    Tile tile1 = tile.getNeighbourOrNull(next);
                    Tile tile2 = tile.getNeighbourOrNull(next2);
                    if (tile2 == null
                        || (type == BorderType.COUNTRY && !owner.owns(tile2))
                        || (type == BorderType.REGION && tile2.getRegion() != region)) {
                        // small corner
                        path.lineTo(borderPoints.get(next).x,
                                borderPoints.get(next).y);
                        path.quadTo(controlPoints.get(next).x,
                                controlPoints.get(next).y,
                                borderPoints.get(next2).x,
                                borderPoints.get(next2).y);
                    } else {
                        int dx = 0, dy = 0;
                        switch(d) {
                        case NW: dx = tileBounds.getHalfWidth(); dy = -tileBounds.getHalfHeight(); break;
                        case NE: dx = tileBounds.getHalfWidth(); dy = tileBounds.getHalfHeight(); break;
                        case SE: dx = -tileBounds.getHalfWidth(); dy = tileBounds.getHalfHeight(); break;
                        case SW: dx = -tileBounds.getHalfWidth(); dy = -tileBounds.getHalfHeight(); break;
                        default: break;
                        }
                        if (tile1 != null
                            && ((type == BorderType.COUNTRY && owner.owns(tile1))
                                || (type == BorderType.REGION && tile1.getRegion() == region))) {
                            // short straight line
                            path.lineTo(borderPoints.get(next).x,
                                    borderPoints.get(next).y);
                            // big corner
                            Direction previous = d.getPreviousDirection();
                            Direction previous2 = previous.getPreviousDirection();
                            int ddx = 0, ddy = 0;
                            switch(d) {
                            case NW: ddy = -tileBounds.getHeight(); break;
                            case NE: ddx = tileBounds.getWidth(); break;
                            case SE: ddy = tileBounds.getHeight(); break;
                            case SW: ddx = -tileBounds.getWidth(); break;
                            default: break;
                            }
                            path.quadTo(controlPoints.get(previous).x + dx,
                                        controlPoints.get(previous).y + dy,
                                        borderPoints.get(previous2).x + ddx,
                                        borderPoints.get(previous2).y + ddy);
                        } else {
                            // straight line
                            path.lineTo(borderPoints.get(d).x + dx,
                                    borderPoints.get(d).y + dy);
                        }
                    }
                } else {
                    path.moveTo(borderPoints.get(next2).x,
                            borderPoints.get(next2).y);
                }
            }
            g2d.draw(path);
            g2d.setColor(oldColor);
            g2d.setStroke(oldStroke);
        }
    }
    
    /**
     * Get either the tile with the active unit or the selected tile,
     * but only if it is visible.
     *
     * Used to determine where to display the cursor, for displayMap and
     * and the cursor action listener.
     *
     * @return The {@code Tile} found or null.
     */
    private Tile getVisibleCursorTile() {
        Tile ret = mapViewerState.getCursorTile();
        return (mapViewerBounds.isTileVisible(ret)) ? ret : null;
    }

    /**
     * Internal class for the {@link MapViewer} that handles what part of the
     * {@link Map} is visible on screen
     * 
     * Methods in this class should only be used by {@link SwingGUI}, {@link Canvas}
     * or {@link MapViewer}.
     */
    public MapViewerBounds getMapViewerBounds() {
        return mapViewerBounds;
    }
    
    /**
     * Bounds of the tiles to be rendered. These bounds are scaled according to the
     * zoom level of the map.
     */
    public TileBounds getTileBounds() {
        return tileBounds;
    }
    
    /**
     * Internal state for the {@link MapViewer}.
     * 
     * Methods in this class should only be used by {@link SwingGUI}, {@link Canvas}
     * or {@link MapViewer}.
     */
    public MapViewerState getMapViewerState() {
        return mapViewerState;
    }
    
    /**
     * Gets the internal class that handles buffers and dirty state of {@link MapViewer}.
     * 
     * Methods in this class should only be used by {@link SwingGUI}, {@link Canvas}
     * or {@link MapViewer}.
     */
    public MapViewerRepaintManager getMapViewerRepaintManager() {
        return rpm;
    }
    
    
    /**
     * Paints a single tile using the provided callback.
     * 
     * @param g2d The {@code Graphics2D} that is used for rendering.
     * @param tcb The bounds used for clipping the area to be rendered.
     * @param tile The tile to be rendered.
     * @param c A callback that should render the tile. The coordinates for the
     *      {@code Graphics2D}, that's provided by the, callback will be
     *      translated so that position (0, 0) is the upper left corner of the
     *      tile image (that is, outside of the tile diamond itself).
     */
    private void paintSingleTile(Graphics2D g2d, TileClippingBounds tcb, Tile tile, TileRenderingCallback c) {
        paintEachTile(g2d, tcb.getTopLeftDirtyTile(), List.of(tile), c);
    }
    
    /**
     * Paints all "dirty" tiles.
     * 
     * @param g2d The {@code Graphics2D} that is used for rendering.
     * @param tcb The bounds used for clipping the area to be rendered.
     * @param tile The tile to be rendered.
     * @param c A callback that should render the tile. The coordinates for the
     *      {@code Graphics2D}, that's provided by the, callback will be
     *      translated so that position (0, 0) is the upper left corner of the
     *      tile image (that is, outside of the tile diamond itself).
     */
    private void paintEachTile(Graphics2D g2d, TileClippingBounds tcb, TileRenderingCallback c) {
        paintEachTile(g2d, tcb.getTopLeftDirtyTile(), tcb.getBaseTiles(), c);
    }
    
    /**
     * Paints all "dirty" tiles and includes
     * {@link TileClippingBounds#getExtendedTiles() extra tiles} to be rendered.
     * 
     * This method should only be used if the rendered graphics can go beyond
     * the tile size.
     * 
     * @param g2d The {@code Graphics2D} that is used for rendering.
     * @param tcb The bounds used for clipping the area to be rendered.
     * @param c A callback that should render the tile. The coordinates for the
     *      {@code Graphics2D}, that's provided by the, callback will be
     *      translated so that position (0, 0) is the upper left corner of the
     *      tile image (that is, outside of the tile diamond itself).
     */
    private void paintEachTileWithExtendedImageSize(Graphics2D g2d, TileClippingBounds tcb, TileRenderingCallback c) {
        paintEachTile(g2d, tcb.getTopLeftDirtyTile(), tcb.getExtendedTiles(), c);
    }
    
    /**
     * Paints all "dirty" tiles and includes
     * {@link TileClippingBounds#getSuperExtendedTiles() many extra tiles} to be rendered.
     * 
     * This method should only be used if the rendered graphics can go way
     * beyond the tile size.
     * 
     * @param g2d The {@code Graphics2D} that is used for rendering.
     * @param tcb The bounds used for clipping the area to be rendered.
     * @param c A callback that should render the tile. The coordinates for the
     *      {@code Graphics2D}, that's provided by the, callback will be
     *      translated so that position (0, 0) is the upper left corner of the
     *      tile image (that is, outside of the tile diamond itself).
     */
    private void paintEachTileWithSuperExtendedImageSize(Graphics2D g2d, TileClippingBounds tcb, TileRenderingCallback c) {
        paintEachTile(g2d, tcb.getTopLeftDirtyTile(), tcb.getSuperExtendedTiles(), c);
    }
    
    private void paintEachTile(Graphics2D g2d, Map.Position firstTile, List<Tile> tiles, TileRenderingCallback c) {
        if (tiles.isEmpty()) {
            return;
        }
        
        final int x0 = firstTile.getX();
        final int y0 = firstTile.getY();
        
        int xt0 = 0, yt0 = 0;
        for (Tile t : tiles) {
            final int x = t.getX();
            final int y = t.getY();
            final int xt = (x-x0) * tileBounds.getWidth()
                + (y&1) * tileBounds.getHalfWidth();
            final int yt = (y-y0) * tileBounds.getHalfHeight();
            g2d.translate(xt - xt0, yt - yt0);
            xt0 = xt; yt0 = yt;

            c.render(g2d, t);
        }
        g2d.translate(-xt0, -yt0);
    }
    
    
    /**
     * Calculates the tile clipping bounds from a Graphics' clipBounds.
     * 
     * The tiles to be redrawn is the area defined by the {@code topLeftDirtyTile}
     * (the upper left corner of the area to be repainted)
     * and {@code bottomRightDirtyTile}
     * (the lower right corner of the area to be repainted).
     * 
     * The list of tiles to be repainted depends on the possible image sizes
     * that can be drawn on a tile: {@link #getBaseTiles() baseTiles},
     * {@link #getExtendedTiles()() extendedTiles} and
     * {@link #getSuperExtendedTiles()() superExtendedTiles}.
     */
    private final class TileClippingBounds {
        
        private final Map.Position topLeftDirtyTile;
        private final Map.Position bottomRightDirtyTile;
        private final int clipLeftX;
        private final int clipTopY;
        
        private final List<Tile> baseTiles;
        private final List<Tile> extendedTiles;
        private final List<Tile> superExtendedTiles;
        
        private TileClippingBounds(Map map, Rectangle clipBounds) {
            // Determine which tiles need to be redrawn          
            final int firstRowTiles = (clipBounds.y - mapViewerBounds.getTopLeftVisibleTilePoint().y) / tileBounds.getHalfHeight() - 1;
            this.clipTopY = mapViewerBounds.getTopLeftVisibleTilePoint().y + firstRowTiles * tileBounds.getHalfHeight();
            final int firstRow = mapViewerBounds.getTopLeftVisibleTile().getY() + firstRowTiles;
            
            final int firstColumnTiles = (clipBounds.x - mapViewerBounds.getTopLeftVisibleTilePoint().x) / tileBounds.getWidth() - 1;
            this.clipLeftX = mapViewerBounds.getTopLeftVisibleTilePoint().x + firstColumnTiles * tileBounds.getWidth();
            final int firstColumn = mapViewerBounds.getTopLeftVisibleTile().getX() + firstColumnTiles;
            
            final int lastRowTiles = (clipBounds.y + clipBounds.height - mapViewerBounds.getTopLeftVisibleTilePoint().y) / tileBounds.getHalfHeight();
            final int lastRow = mapViewerBounds.getTopLeftVisibleTile().getY() + lastRowTiles;
            
            final int lastColumnTiles = (clipBounds.x + clipBounds.width - mapViewerBounds.getTopLeftVisibleTilePoint().x) / tileBounds.getWidth();
            final int lastColumn = mapViewerBounds.getTopLeftVisibleTile().getX() + lastColumnTiles;
            
            this.topLeftDirtyTile = new Map.Position(firstColumn, firstRow);
            this.bottomRightDirtyTile = new Map.Position(lastColumn, lastRow);
            
            final int subMapWidth = bottomRightDirtyTile.getX() - topLeftDirtyTile.getX() + 1;
            final int subMapHeight = bottomRightDirtyTile.getY() - topLeftDirtyTile.getY() + 1;
            
            baseTiles = map.subMap(
                    topLeftDirtyTile.getX(),
                    topLeftDirtyTile.getY(),
                    subMapWidth,
                    subMapHeight);
            
            extendedTiles = map.subMap(
                    topLeftDirtyTile.getX(),
                    topLeftDirtyTile.getY() - 1,
                    subMapWidth,
                    subMapHeight + 1);
            
            superExtendedTiles = map.subMap(
                    topLeftDirtyTile.getX() - 2,
                    topLeftDirtyTile.getY() - 4,
                    subMapWidth + 4,
                    subMapHeight + 8);
        }

        public Map.Position getTopLeftDirtyTile() {
            return topLeftDirtyTile;
        }
        
        public Map.Position getBottomRightDirtyTile() {
            return bottomRightDirtyTile;
        }
        
        /**
         * The tiles to be repainted for graphics that does not extend beyond the
         * tile size.
         */
        public List<Tile> getBaseTiles() {
            return baseTiles;
        }
        
        /**
         * The tiles to be repainted for graphics that might have double height
         * compared to the tile size.
         */
        public List<Tile> getExtendedTiles() {
            return extendedTiles;
        }
        
        /**
         * The tiles to be repainted for graphics that might extend far into other
         * tiles in every direction (typically halos, like in revenge mode).
         */
        public List<Tile> getSuperExtendedTiles() {
            return superExtendedTiles;
        }
    }
    
    /**
     * A callback for rendering a single tile.
     */
    private interface TileRenderingCallback {
        
        /**
         * Should render a single tile.
         * 
         * @param tileG2d The {@code Graphics2D} that should be used when drawing
         *      the tile. The coordinates for the {@code Graphics2D} will be
         *      translated so that position (0, 0) is the upper left corner of
         *      the tile image (that is, outside of the tile diamond itself).
         * @param tile The {@code Tile} to be rendered. 
         */
        void render(Graphics2D tileG2d, Tile tile);
    }
    
    private static class TextSpecification {

        public final String text;
        public final Font font;

        public TextSpecification(String newText, Font newFont) {
            this.text = newText;
            this.font = newFont;
        }
    }
}
