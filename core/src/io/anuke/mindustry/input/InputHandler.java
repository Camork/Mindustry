package io.anuke.mindustry.input;

import io.anuke.annotations.Annotations.Loc;
import io.anuke.annotations.Annotations.Remote;
import io.anuke.arc.Core;
import io.anuke.arc.collection.Array;
import io.anuke.arc.function.Consumer;
import io.anuke.arc.graphics.Color;
import io.anuke.arc.graphics.g2d.Draw;
import io.anuke.arc.graphics.g2d.TextureRegion;
import io.anuke.arc.input.InputProcessor;
import io.anuke.arc.math.Angles;
import io.anuke.arc.math.Mathf;
import io.anuke.arc.math.geom.Point2;
import io.anuke.arc.math.geom.Vector2;
import io.anuke.arc.scene.ui.layout.Table;
import io.anuke.arc.util.Time;
import io.anuke.arc.util.Tmp;
import io.anuke.mindustry.content.Blocks;
import io.anuke.mindustry.content.Fx;
import io.anuke.mindustry.entities.Effects;
import io.anuke.mindustry.entities.effect.ItemTransfer;
import io.anuke.mindustry.entities.traits.BuilderTrait.BuildRequest;
import io.anuke.mindustry.entities.type.Player;
import io.anuke.mindustry.gen.Call;
import io.anuke.mindustry.graphics.Pal;
import io.anuke.mindustry.net.Net;
import io.anuke.mindustry.net.ValidateException;
import io.anuke.mindustry.type.ItemStack;
import io.anuke.mindustry.ui.fragments.OverlayFragment;
import io.anuke.mindustry.world.*;

import static io.anuke.mindustry.Vars.*;

public abstract class InputHandler implements InputProcessor{
    /** Used for dropping items. */
    final static float playerSelectRange = mobile ? 17f : 11f;
    /** Maximum line length. */
    final static int maxLength = 100;
    /**Distance on the back from where items originate.*/
    final static float backTrns = 3f;

    public final OverlayFragment frag = new OverlayFragment(this);

    public Block block;
    public int rotation;
    public boolean droppingItem;

    protected PlaceDraw placeDraw = new PlaceDraw();
    private PlaceLine line = new PlaceLine();

    //methods to override

    @Remote(targets = Loc.client, called = Loc.server)
    public static void dropItem(Player player, float angle){
        if(Net.server() && player.item().amount <= 0){
            throw new ValidateException(player, "Player cannot drop an item.");
        }

        Effects.effect(Fx.dropItem, Color.WHITE, player.x, player.y, angle, player.item().item);
        player.clearItem();
    }

    @Remote(targets = Loc.both, forward = true, called = Loc.server)
    public static void transferInventory(Player player, Tile tile){
        if(player == null || tile.entity == null || !tile.block().acceptItem(player.item().item, tile)) return;

        Block block = tile.block();
        ItemStack stack = player.item();

        for(int i = 0; i < stack.amount; i++){
            Time.run(i, () -> {
                stack.amount --;

                if(tile.block().acceptItem(stack.item, tile)){
                    ItemTransfer.create(stack.item,
                    player.x + Angles.trnsx(player.rotation + 180f, backTrns), player.y + Angles.trnsy(player.rotation + 180f, backTrns),
                    new Vector2(tile.drawx(), tile.drawy()), () -> {
                        if(tile.block() != block || tile.entity == null || tile.entity.items == null) return;

                        if(tile.block().acceptItem(stack.item, tile)){
                            tile.block().handleItem(stack.item, tile);
                            stack.amount --;
                        }
                    });
                }
            });
        }
    }

    @Remote(targets = Loc.both, called = Loc.server, forward = true)
    public static void onTileTapped(Player player, Tile tile){
        if(tile == null || player == null) return;
        tile.block().tapped(tile, player);
    }

    public OverlayFragment getFrag(){
        return frag;
    }

    public void update(){

    }

    public float getMouseX(){
        return Core.input.mouseX();
    }

    public float getMouseY(){
        return Core.input.mouseY();
    }

    public void buildUI(Table table){

    }

    public void updateController(){

    }

    public void drawOutlined(){

    }

    public void drawTop(){

    }

    public boolean isDrawing(){
        return false;
    }

    /** Handles tile tap events that are not platform specific. */
    boolean tileTapped(Tile tile){
        tile = tile.link();

        boolean consumed = false, showedInventory = false;

        //check if tapped block is configurable
        if(tile.block().configurable && tile.interactable(player.getTeam())){
            consumed = true;
            if(((!frag.config.isShown() && tile.block().shouldShowConfigure(tile, player)) //if the config fragment is hidden, show
            //alternatively, the current selected block can 'agree' to switch config tiles
            || (frag.config.isShown() && frag.config.getSelectedTile().block().onConfigureTileTapped(frag.config.getSelectedTile(), tile)))){
                frag.config.showConfig(tile);
            }
            //otherwise...
        }else if(!frag.config.hasConfigMouse()){ //make sure a configuration fragment isn't on the cursor
            //then, if it's shown and the current block 'agrees' to hide, hide it.
            if(frag.config.isShown() && frag.config.getSelectedTile().block().onConfigureTileTapped(frag.config.getSelectedTile(), tile)){
                consumed = true;
                frag.config.hideConfig();
            }

            if(frag.config.isShown()){
                consumed = true;
            }
        }

        //call tapped event
        if(!consumed && tile.interactable(player.getTeam())){
            Call.onTileTapped(player, tile);
        }

        //consume tap event if necessary
        if(tile.interactable(player.getTeam()) && tile.block().consumesTap){
            consumed = true;
        }else if(tile.interactable(player.getTeam()) && tile.block().synthetic() && !consumed){
            if(tile.block().hasItems && tile.entity.items.total() > 0){
                frag.inv.showFor(tile);
                consumed = true;
                showedInventory = true;
            }
        }

        //clear when the player taps on something else
        if(!consumed && !mobile && player.isBuilding() && block == null){
            player.clearBuilding();
            block = null;
            return true;
        }

        if(!showedInventory){
            frag.inv.hide();
        }

        return consumed;
    }

    /** Tries to select the player to drop off items, returns true if successful. */
    boolean tryTapPlayer(float x, float y){
        if(canTapPlayer(x, y)){
            droppingItem = true;
            return true;
        }
        return false;
    }

    boolean canTapPlayer(float x, float y){
        return Mathf.dst(x, y, player.x, player.y) <= playerSelectRange && player.item().amount > 0;
    }

    /** Tries to begin mining a tile, returns true if successful. */
    boolean tryBeginMine(Tile tile){
        if(canMine(tile)){
            //if a block is clicked twice, reset it
            player.setMineTile(player.getMineTile() == tile ? null : tile);
            return true;
        }
        return false;
    }

    boolean canMine(Tile tile){
        return !Core.scene.hasMouse()
        && tile.drop() != null && tile.drop().hardness <= player.mech.drillPower
        && !(tile.floor().playerUnmineable && tile.overlay().itemDrop == null)
        && player.acceptsItem(tile.drop())
        && tile.block() == Blocks.air && player.dst(tile.worldx(), tile.worldy()) <= Player.mineDistance;
    }

    /** Returns the tile at the specified MOUSE coordinates. */
    Tile tileAt(float x, float y){
        return world.tile(tileX(x), tileY(y));
    }

    int tileX(float cursorX){
        Vector2 vec = Core.input.mouseWorld(cursorX, 0);
        if(selectedBlock()){
            vec.sub(block.offset(), block.offset());
        }
        return world.toTile(vec.x);
    }

    int tileY(float cursorY){
        Vector2 vec = Core.input.mouseWorld(0, cursorY);
        if(selectedBlock()){
            vec.sub(block.offset(), block.offset());
        }
        return world.toTile(vec.y);
    }

    public boolean selectedBlock(){
        return isPlacing();
    }

    public boolean isPlacing(){
        return block != null;
    }

    public float mouseAngle(float x, float y){
        return Core.input.mouseWorld(getMouseX(), getMouseY()).sub(x, y).angle();
    }

    public void remove(){
        Core.input.removeProcessor(this);
        frag.remove();
    }

    public boolean canShoot(){
        return block == null && !Core.scene.hasMouse() && !onConfigurable() && !isDroppingItem();
    }

    public boolean onConfigurable(){
        return false;
    }

    public boolean isDroppingItem(){
        return droppingItem;
    }

    public void tryDropItems(Tile tile, float x, float y){
        if(!droppingItem || player.item().amount <= 0 || canTapPlayer(x, y) || state.isPaused()){
            droppingItem = false;
            return;
        }

        droppingItem = false;

        ItemStack stack = player.item();

        if(tile.block().acceptItem(stack.item, tile) && tile.interactable(player.getTeam())&& tile.block().hasItems){
            Call.transferInventory(player, tile);
        }else{
            Call.dropItem(player.angleTo(x, y));
        }
    }

    public void tryPlaceBlock(int x, int y){
        if(block != null && validPlace(x, y, block, rotation)){
            placeBlock(x, y, block, rotation);
        }
    }

    public void tryBreakBlock(int x, int y){
        if(validBreak(x, y)){
            breakBlock(x, y);
        }
    }

    public boolean validPlace(int x, int y, Block type, int rotation){
        return Build.validPlace(player.getTeam(), x, y, type, rotation);
    }

    public boolean validBreak(int x, int y){
        return Build.validBreak(player.getTeam(), x, y) && Mathf.dst(player.x, player.y, x * tilesize, y * tilesize) < Player.placeDistance;
    }

    public void placeBlock(int x, int y, Block block, int rotation){
        player.addBuildRequest(new BuildRequest(x, y, rotation, block));
    }

    public void breakBlock(int x, int y){
        Tile tile = world.ltile(x, y);
        player.addBuildRequest(new BuildRequest(tile.x, tile.y));
    }

    void drawArrow(Block block, int x, int y, int rotation){
        Draw.color(!validPlace(x, y, block, rotation) ? Pal.removeBack : Pal.accentBack);
        Draw.rect(Core.atlas.find("place-arrow"),
        x * tilesize + block.offset(),
        y * tilesize + block.offset() - 1,
        Core.atlas.find("place-arrow").getWidth() * Draw.scl,
        Core.atlas.find("place-arrow").getHeight() * Draw.scl, rotation * 90 - 90);

        Draw.color(!validPlace(x, y, block, rotation) ? Pal.remove : Pal.accent);
        Draw.rect(Core.atlas.find("place-arrow"),
        x * tilesize + block.offset(),
        y * tilesize + block.offset(),
        Core.atlas.find("place-arrow").getWidth() * Draw.scl,
        Core.atlas.find("place-arrow").getHeight() * Draw.scl, rotation * 90 - 90);
    }

    void iterateLine(int startX, int startY, int endX, int endY, Consumer<PlaceLine> cons){
        Array<Point2> points;
        boolean diagonal = Core.input.keyDown(Binding.diagonal_placement);
        if(Core.settings.getBool("swapdiagonal")){
            diagonal = !diagonal;
        }

        if(diagonal){
            points = PlaceUtils.normalizeDiagonal(startX, startY, endX, endY);
        }else{
            points = PlaceUtils.normalizeLine(startX, startY, endX, endY);
        }

        float angle = Angles.angle(startX, startY, endX, endY);
        int baseRotation = (startX == endX && startY == endY) ? rotation : ((int)((angle + 45) / 90f)) % 4;

        Tmp.r3.set(-1, -1, 0, 0);

        for(int i = 0; i < points.size; i++){
            Point2 point = points.get(i);

            if(block != null && Tmp.r2.setSize(block.size * tilesize).setCenter(point.x * tilesize + block.offset(), point.y * tilesize + block.offset()).overlaps(Tmp.r3)){
                continue;
            }

            Point2 next = i == points.size - 1 ? null : points.get(i + 1);
            line.x = point.x;
            line.y = point.y;
            line.rotation = next != null ? Tile.relativeTo(point.x, point.y, next.x, next.y) : baseRotation;
            line.last = next == null;
            cons.accept(line);

            Tmp.r3.setSize(block.size * tilesize).setCenter(point.x * tilesize + block.offset(), point.y * tilesize + block.offset());
        }
    }

    public static class PlaceDraw{
        public int rotation, scalex, scaley;
        public TextureRegion region;

        public static final PlaceDraw instance = new PlaceDraw();
    }

    class PlaceLine{
        public int x, y, rotation;
        public boolean last;
    }
}
