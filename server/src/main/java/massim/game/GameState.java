package massim.game;

import massim.config.TeamConfig;
import massim.game.environment.*;
import massim.protocol.data.Position;
import massim.protocol.data.Thing;
import massim.protocol.messages.RequestActionMessage;
import massim.protocol.messages.SimEndMessage;
import massim.protocol.messages.SimStartMessage;
import massim.protocol.messages.scenario.Actions;
import massim.protocol.messages.scenario.InitialPercept;
import massim.protocol.messages.scenario.StepPercept;
import massim.util.Log;
import massim.util.RNG;
import massim.util.Util;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * State of the game.
 */
class GameState {

    private static Map<Integer, Terrain> terrainColors =
            Map.of(-16777216, Terrain.OBSTACLE, -1, Terrain.EMPTY, -65536, Terrain.GOAL);

    private Map<String, Team> teams = new HashMap<>();
    private Map<String, Entity> agentToEntity = new HashMap<>();
    private Map<Entity, String> entityToAgent = new HashMap<>();

    private int step = -1;
    private Grid grid;
    private Map<String, GameObject> gameObjects = new HashMap<>();
    private Map<Position, Dispenser> dispensers = new HashMap<>();
    private Map<String, Task> tasks = new HashMap<>();
    private Set<String> blockTypes = new TreeSet<>();
    private Set<ClearEvent> clearEvents = new HashSet<>();

    // config parameters
    private int randomFail;
    private double pNewTask;
    private int taskDurationMin;
    private int taskDurationMax;
    private int taskSizeMin;
    private int taskSizeMax;
    private int clearSteps;
    private int eventChance;
    private int eventRadiusMin;
    private int eventRadiusMax;
    private int eventWarning;
    private int eventCreateMin;
    private int eventCreateMax;

    GameState(JSONObject config, Set<TeamConfig> matchTeams) {
        // parse simulation config
        randomFail = config.getInt("randomFail");
        Log.log(Log.Level.NORMAL, "config.randomFail: " + randomFail);
        int attachLimit = config.getInt("attachLimit");
        Log.log(Log.Level.NORMAL, "config.attachLimit: " + attachLimit);
        clearSteps = config.getInt("clearSteps");
        Log.log(Log.Level.NORMAL, "config.clearSteps: " + clearSteps);

        Entity.clearEnergyCost = config.getInt("clearEnergyCost");
        Log.log(Log.Level.NORMAL, "config.clearEnergyCost: " + Entity.clearEnergyCost);
        Entity.disableDuration = config.getInt("disableDuration");
        Log.log(Log.Level.NORMAL, "config.disableDuration: " + Entity.disableDuration);
        Entity.maxEnergy = config.getInt("maxEnergy");
        Log.log(Log.Level.NORMAL, "config.maxEnergy: " + Entity.maxEnergy);

        var blockTypeBounds = config.getJSONArray("blockTypes");
        var numberOfBlockTypes = RNG.betweenClosed(blockTypeBounds.getInt(0), blockTypeBounds.getInt(1));
        Log.log(Log.Level.NORMAL, "config.blockTypes: " + blockTypeBounds + " -> " + numberOfBlockTypes);
        for (int i = 0; i < numberOfBlockTypes; i++) {
            blockTypes.add("b" + i);
        }
        var dispenserBounds = config.getJSONArray("dispensers");
        Log.log(Log.Level.NORMAL, "config.dispensersBounds: " + dispenserBounds);

        var taskConfig = config.getJSONObject("tasks");
        var taskDurationBounds = taskConfig.getJSONArray("duration");
        Log.log(Log.Level.NORMAL, "config.tasks.duration: " + taskDurationBounds);
        taskDurationMin = taskDurationBounds.getInt(0);
        taskDurationMax = taskDurationBounds.getInt(1);
        var taskSizeBounds = taskConfig.getJSONArray("size");
        Log.log(Log.Level.NORMAL, "config.tasks.size: " + taskSizeBounds);
        taskSizeMin = taskSizeBounds.getInt(0);
        taskSizeMax = taskSizeBounds.getInt(1);
        pNewTask = taskConfig.getDouble("probability");
        Log.log(Log.Level.NORMAL, "config.tasks.probability: " + pNewTask);

        var eventConfig = config.getJSONObject("events");
        eventChance = eventConfig.getInt("chance");
        var eventRadius = eventConfig.getJSONArray("radius");
        eventRadiusMin = eventRadius.getInt(0);
        eventRadiusMax = eventRadius.getInt(1);
        eventWarning = eventConfig.getInt("warning");
        var eventCreate = eventConfig.getJSONArray("create");
        eventCreateMin = eventCreate.getInt(0);
        eventCreateMax = eventCreate.getInt(1);

        // create teams
        matchTeams.forEach(team -> teams.put(team.getName(), new Team(team.getName())));

        // create grid environment
        JSONObject gridConf = config.getJSONObject("grid");
        var gridX = gridConf.getInt("width");
        var gridY = gridConf.getInt("height");
        grid = new Grid(gridX, gridY, attachLimit);

        // read bitmap if available
        String mapFilePath = gridConf.optString("file");
        if (!mapFilePath.equals("")){
            var mapFile = new File(mapFilePath);
            if (mapFile.exists()) {
                try {
                    BufferedImage img = ImageIO.read(mapFile);
                    var width = Math.min(gridX, img.getWidth());
                    var height = Math.min(gridY, img.getHeight());
                    for (int x = 0; x < width; x++) { for (int y = 0; y < height; y++) {
                        grid.setTerrain(x, y, terrainColors.getOrDefault(img.getRGB(x, y), Terrain.EMPTY));
                    }}
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            else Log.log(Log.Level.ERROR, "File " + mapFile.getAbsolutePath() + " not found.");
        }

        // create entities
        JSONArray entities = config.getJSONArray("entities");
        for (var type = 0; type < entities.length(); type++) {
            var entityConf = entities.optJSONObject(type);
            if (entityConf != null){
                var roleName = entityConf.keys().next();
                var amount = entityConf.optInt(roleName, 0);
                for (var n = 0; n < amount; n++){
                    var position = grid.findRandomFreePosition(); // entities from the same team start on the same spot
                    for (TeamConfig team: matchTeams) {
                        String agentName;
                        if(n < team.getAgentNames().size()) {
                            agentName = team.getAgentNames().get(n);
                        }
                        else {
                            agentName = team.getName() + "-unconfigured-" + n;
                            Log.log(Log.Level.ERROR, "Too few agents configured for team " + team.getName()
                                    + ", using agent name " + agentName + ".");
                        }
                        createEntity(position, agentName, team.getName());
                    }
                }
            }
        }

        // create env. things
        for (var block : blockTypes) {
            var numberOfDispensers = RNG.betweenClosed(dispenserBounds.getInt(0), dispenserBounds.getInt(1));
            for (var i = 0; i < numberOfDispensers; i++) {
                createDispenser(grid.findRandomFreePosition(), block);
            }
        }

        // check for setup file
        var setupFilePath = config.optString("setup");
        if (!setupFilePath.equals("")){
            Log.log(Log.Level.NORMAL, "Running setup actions");
            try {
                var b = new BufferedReader(new FileReader(setupFilePath));
                var line = "";
                while ((line = b.readLine()) != null) {
                    if (line.startsWith("#")) continue;
                    handleCommand(line.split(" "));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    Map<String, Team> getTeams() {
        return this.teams;
    }

    public Set<String> getBlockTypes() {
        return this.blockTypes;
    }

    private void handleCommand(String[] command) {
        switch (command[0]) {
            case "move":
                if (command.length != 4) break;
                var x = Util.tryParseInt(command[1]);
                var y = Util.tryParseInt(command[2]);
                var entity = agentToEntity.get(command[3]);

                if (entity == null || x == null || y == null) break;
                Log.log(Log.Level.NORMAL, "Setup: Try to move " + command[3] + " to (" + x +", " + y + ")");
                grid.moveWithoutAttachments(entity, Position.of(x, y));
                break;

            case "add":
                if (command.length != 5) break;
                x = Util.tryParseInt(command[1]);
                y = Util.tryParseInt(command[2]);
                if (x == null || y == null) break;
                switch (command[3]) {
                    case "block":
                        var blockType = command[4];
                        createBlock(Position.of(x, y), blockType);
                        break;
                    case "dispenser":
                        blockType = command[4];
                        createDispenser(Position.of(x, y), blockType);
                        break;
                    default:
                        Log.log(Log.Level.ERROR, "Cannot add " + command[3]);
                }
                break;

            case "create":
                if (command.length != 5) break;
                if (command[1].equals("task")) {
                    var name = command[2];
                    var duration = Util.tryParseInt(command[3]);
                    var requirements = command[4].split(";");
                    if (duration == null) break;
                    var requireMap = new HashMap<Position, String>();
                    Arrays.stream(requirements).map(req -> req.split(",")).forEach(req -> {
                        var bx = Util.tryParseInt(req[0]);
                        var by = Util.tryParseInt(req[1]);
                        var blockType = req[2];
                        if (bx != null && by != null) {
                            requireMap.put(Position.of(bx, by), blockType);
                        }
                    });
                    createTask(name, duration, requireMap);
                }
                break;

            case "attach":
                if (command.length != 5) break;
                var x1 = Util.tryParseInt(command[1]);
                var y1 = Util.tryParseInt(command[2]);
                var x2 = Util.tryParseInt(command[3]);
                var y2 = Util.tryParseInt(command[4]);
                if (x1 == null || x2 == null || y1 == null || y2 == null) break;
                Attachable a1 = getUniqueAttachable(Position.of(x1, y1));
                Attachable a2 = getUniqueAttachable(Position.of(x2, y2));
                if (a1 == null || a2 == null) break;
                grid.attach(a1, a2);
                break;
            default:
                Log.log(Log.Level.ERROR, "Cannot handle command " + Arrays.toString(command));
        }
    }

    int getRandomFail() {
        return this.randomFail;
    }

    public Grid getGrid() {
        return grid;
    }

    Entity getEntityByName(String agentName) {
        return agentToEntity.get(agentName);
    }

    Map<String, SimStartMessage> getInitialPercepts(int steps) {
        Map<String, SimStartMessage> result = new HashMap<>();
        for (Entity e: entityToAgent.keySet()) {
            result.put(e.getAgentName(), new InitialPercept(e.getAgentName(), e.getTeamName(), steps, e.getVision()));
        }
        return result;
    }

    Map<String, RequestActionMessage> prepareStep(int step) {
        this.step = step;

        //cleanup
        grid.deleteMarkers();

        //handle tasks
        if (RNG.nextDouble() < pNewTask) {
            createTask(RNG.betweenClosed(taskDurationMin, taskDurationMax), RNG.betweenClosed(taskSizeMin, taskSizeMax));
        }

        //handle entities
        agentToEntity.values().forEach(Entity::preStep);

        //handle (map) events
        if (RNG.nextInt(100) < eventChance) {
            clearEvents.add(new ClearEvent(grid.getRandomPosition(), step + eventWarning,
                    RNG.betweenClosed(eventRadiusMin, eventRadiusMax)));
        }
        var processedEvents = new HashSet<ClearEvent>();
        for (ClearEvent event: clearEvents) {
            if (event.getStep() == step) {
                processEvent(event);
                processedEvents.add(event);
            }
            else {
                for (Position pos: new Area(event.getPosition(), event.getRadius())) {
                    grid.createMarker(pos, Marker.Type.CLEAR);
                }
            }
        }
        clearEvents.removeAll(processedEvents);

        return getStepPercepts();
    }

    private void processEvent(ClearEvent event) {
        var removed = clearArea(event.getPosition(), event.getRadius());
        var distributeNew = RNG.betweenClosed(eventCreateMin, eventCreateMax) + removed;

        for (var i = 0; i < distributeNew; i++) {
            var pos = grid.findRandomFreePosition(event.getPosition(),event.getRadius() + 3);
            if(grid.isInBounds(pos)) {
                grid.setTerrain(pos.x, pos.y, Terrain.OBSTACLE);
            }
        }
    }

    private Map<String, RequestActionMessage> getStepPercepts(){
        Map<String, RequestActionMessage> result = new HashMap<>();
        var allTasks = tasks.values().stream()
                .filter(t -> !t.isCompleted())
                .map(Task::toPercept)
                .collect(Collectors.toSet());
        for (Entity entity : entityToAgent.keySet()) {
            var pos = entity.getPosition();
            var visibleThings = new HashSet<Thing>();
            Map<String, Set<Position>> visibleTerrain = new HashMap<>();
            Set<Position> attachedThings = new HashSet<>();
            for (Position currentPos: new Area(pos, entity.getVision())){
                getGameObjects(currentPos).forEach(go -> {
                    visibleThings.add(go.toPercept(pos));
                    if (go instanceof Attachable && ((Attachable)go).isAttachedToAnotherEntity()){
                        attachedThings.add(go.getPosition().toLocal(pos));
                    }
                });
                var d = dispensers.get(currentPos);
                if (d != null) visibleThings.add(d.toPercept(pos));
                var terrain = grid.getTerrain(currentPos);
                if (terrain != Terrain.EMPTY) {
                    visibleTerrain.computeIfAbsent(terrain.name,
                            t -> new HashSet<>()).add(currentPos.toLocal(pos));
                }
            }
            var percept = new StepPercept(step, teams.get(entity.getTeamName()).getScore(),
                    visibleThings, visibleTerrain, allTasks, entity.getLastAction(), entity.getLastActionParams(),
                    entity.getLastActionResult(), attachedThings);
            percept.energy = entity.getEnergy();
            percept.disabled = entity.isDisabled();
            result.put(entity.getAgentName(), percept);
        }
        return result;
    }

    Map<String, SimEndMessage> getFinalPercepts() {
        Map<String, SimEndMessage> result = new HashMap<>();
        List<Team> teamsSorted = new ArrayList<>(teams.values());
        teamsSorted.sort((t1, t2) -> (int) (t2.getScore() - t1.getScore()));
        Map<Team, Integer> rankings = new HashMap<>();
        for (int i = 0; i < teamsSorted.size(); i++) {
            rankings.put(teamsSorted.get(i), i + 1);
        }
        for (Entity e: entityToAgent.keySet()) {
            Team team = teams.get(e.getTeamName());
            result.put(e.getAgentName(), new SimEndMessage(team.getScore(), rankings.get(team)));
        }
        return result;
    }

    String handleMoveAction(Entity entity, String direction) {
        if (grid.moveWithAttached(entity, direction, 1)) {
            return Actions.RESULT_SUCCESS;
        }
        return Actions.RESULT_F_PATH;
    }

    String handleRotateAction(Entity entity, boolean clockwise) {
        if (grid.rotateWithAttached(entity, clockwise)) {
            return Actions.RESULT_SUCCESS;
        }
        return Actions.RESULT_F;
    }

    String handleAttachAction(Entity entity, String direction) {
        Position target = entity.getPosition().moved(direction, 1);
        Attachable a = getUniqueAttachable(target);
        if (a == null) return Actions.RESULT_F_TARGET;
        if (a instanceof Entity && ofDifferentTeams(entity, (Entity) a)) {
            return Actions.RESULT_F_TARGET;
        }
        if(!attachedToOpponent(a, entity) && grid.attach(entity, a)) {
            return Actions.RESULT_SUCCESS;
        }
        return Actions.RESULT_F;
    }

    String handleDetachAction(Entity entity, String direction) {
        Position target = entity.getPosition().moved(direction, 1);
        Attachable a = getUniqueAttachable(target);
        if (a == null) return Actions.RESULT_F_TARGET;
        if (a instanceof Entity && ofDifferentTeams(entity, (Entity) a)) {
            return Actions.RESULT_F_TARGET;
        }
        if (grid.detach(entity, a)){
            return Actions.RESULT_SUCCESS;
        }
        return Actions.RESULT_F;
    }

    String handleConnectAction(Entity entity, Position blockPos, Entity partnerEntity, Position partnerBlockPos) {
        Attachable block1 = getUniqueAttachable(blockPos.translate(entity.getPosition()));
        Attachable block2 = getUniqueAttachable(partnerBlockPos.translate(partnerEntity.getPosition()));

        if(!(block1 instanceof Block) || !(block2 instanceof Block)) return Actions.RESULT_F_TARGET;

        Set<Attachable> attachables = entity.collectAllAttachments();
        if (attachables.contains(partnerEntity)) return Actions.RESULT_F;
        if (!attachables.contains(block1)) return Actions.RESULT_F_TARGET;
        if (attachables.contains(block2)) return Actions.RESULT_F_TARGET;

        Set<Attachable> partnerAttachables = partnerEntity.collectAllAttachments();
        if (!partnerAttachables.contains(block2)) return Actions.RESULT_F_TARGET;
        if (partnerAttachables.contains(block1)) return Actions.RESULT_F_TARGET;

        if(grid.attach(block1, block2)){
            return Actions.RESULT_SUCCESS;
        }
        return Actions.RESULT_F;
    }

    String handleRequestAction(Entity entity, String direction) {
        var requestPosition = entity.getPosition().moved(direction, 1);
        var dispenser = dispensers.get(requestPosition);
        if (dispenser == null) return Actions.RESULT_F_TARGET;
        if (!grid.isUnblocked(requestPosition)) return Actions.RESULT_F_BLOCKED;
        createBlock(requestPosition, dispenser.getBlockType());
        return Actions.RESULT_SUCCESS;
    }

    String handleSubmitAction(Entity e, String taskName) {
        Task task = tasks.get(taskName);
        if (task == null || task.isCompleted()) return Actions.RESULT_F_TARGET;
        Position ePos = e.getPosition();
        if (grid.getTerrain(ePos) != Terrain.GOAL) return Actions.RESULT_F;
        Set<Attachable> attachedBlocks = e.collectAllAttachments();
        for (Map.Entry<Position, String> entry : task.getRequirements().entrySet()) {
            Position pos = entry.getKey();
            String reqType = entry.getValue();
            Position checkPos = Position.of(pos.x + ePos.x, pos.y + ePos.y);
            Attachable actualBlock = getUniqueAttachable(checkPos);
            if (actualBlock instanceof Block
                && ((Block) actualBlock).getBlockType().equals(reqType)
                && attachedBlocks.contains(actualBlock)) {
                continue;
            }
            return Actions.RESULT_F;
        }
        task.getRequirements().keySet().forEach(pos -> {
            Attachable a = getUniqueAttachable(pos.translate(e.getPosition()));
            removeObjectFromGame(a);
        });
        teams.get(e.getTeamName()).addScore(task.getReward());
        task.complete();
        return Actions.RESULT_SUCCESS;
    }

    /**
     * @param entity entity executing the action
     * @param xy target position in entity local system
     * @return action result
     */
    String handleClearAction(Entity entity, Position xy) {
        var target = xy.translate(entity.getPosition());
        if (target.distanceTo(entity.getPosition()) > entity.getVision()) return Actions.RESULT_F_TARGET;
        if (grid.isInBounds(target)) {
            if (entity.getEnergy() < Entity.clearEnergyCost) return Actions.RESULT_F_STATUS;
            var previousPos = entity.getPreviousClearPosition();

            if(entity.getPreviousClearStep() != step - 1 || previousPos.x != target.x || previousPos.y != target.y) {
                entity.resetClearCounter();
            }
            var counter = entity.incrementClearCounter();
            if (counter == clearSteps) {
                clearArea(target, 1);
                entity.consumeClearEnergy();
                entity.resetClearCounter();
            }
            else {
                for (Position position: new Area(target, 1)) {
                    grid.createMarker(position, Marker.Type.CLEAR);
                }
            }
            entity.recordClearAction(step, target);
            return Actions.RESULT_SUCCESS;
        }
        else{
            return Actions.RESULT_F_TARGET;
        }
    }

    int clearArea(Position center, int radius) {
        var removed = 0;
        for (Position position : new Area(center, radius)) {
            for (var go : getGameObjects(position)) {
                if (go instanceof Entity) {
                    ((Entity)go).disable();
                }
                else if (go instanceof Block) {
                    removed++;
                    grid.removeThing((Positionable) go);
                }
                else if (grid.getTerrain(position) == Terrain.OBSTACLE) {
                    removed++;
                    grid.setTerrain(position.x, position.y, Terrain.EMPTY);
                }
            }
        }
        return removed;
    }

    Task createTask(int duration, int size) {
        if (size < 1) return null;
        var name = "task" + tasks.values().size();
        var requirements = new HashMap<Position, String>();
        var blockList = new ArrayList<>(blockTypes);
        Position lastPosition = Position.of(0, 1);
        requirements.put(lastPosition, blockList.get(RNG.nextInt(blockList.size())));
        for (int i = 0; i < size - 1; i++) {
            int index = RNG.nextInt(blockTypes.size());
            double direction = RNG.nextDouble();
            if (direction <= .3) {
                lastPosition = lastPosition.translate(-1, 0);
            }
            else if (direction <= .6) {
                lastPosition = lastPosition.translate(1, 0);
            }
            else {
                lastPosition = lastPosition.translate(0, 1);
            }
            requirements.put(lastPosition, blockList.get(index));
        }
        Task t = new Task(name, step + duration, requirements);
        tasks.put(t.getName(), t);
        return t;
    }

    Task createTask(String name, int duration, Map<Position, String> requirements) {
        if (requirements.size() == 0) return null;
        Task t = new Task(name, step + duration, requirements);
        tasks.put(t.getName(), t);
        return t;
    }

    private void removeObjectFromGame(GameObject go){
        if (go == null) return;
        if (go instanceof Positionable) grid.removeThing((Positionable) go);
        gameObjects.remove(go.getID());
    }

    private Entity createEntity(Position xy, String name, String teamName) {
        Entity e = grid.createEntity(xy, name, teamName);
        registerGameObject(e);
        agentToEntity.put(name, e);
        entityToAgent.put(e, name);
        return e;
    }

    Block createBlock(Position xy, String blockType) {
        if (!blockTypes.contains(blockType)) return null;
        Block b = grid.createBlock(xy, blockType);
        registerGameObject(b);
        return b;
    }

    boolean createDispenser(Position xy, String blockType) {
        if (!blockTypes.contains(blockType)) return false;
        if (!grid.isUnblocked(xy)) return false;
        Dispenser d = new Dispenser(xy, blockType);
        registerGameObject(d);
        dispensers.put(xy, d);
        Log.log(Log.Level.NORMAL, "Created " + d);
        return true;
    }

    private void registerGameObject(GameObject o) {
        if (o == null) return;
        this.gameObjects.put(o.getID(), o);
    }

    private Attachable getUniqueAttachable(Position pos) {
        var attachables = getAttachables(pos);
        if (attachables.size() != 1) return null;
        return attachables.iterator().next();
    }

    private Set<Attachable> getAttachables(Position position) {
        return getGameObjects(position).stream()
                .filter(go -> go instanceof Attachable)
                .map(go -> (Attachable)go)
                .collect(Collectors.toSet());
    }

    private Set<Positionable> getGameObjects(Position pos) {
        return grid.getThings(pos);
    }

    private boolean attachedToOpponent(Attachable a, Entity entity) {
        return a.collectAllAttachments().stream().anyMatch(other -> other instanceof Entity && ofDifferentTeams((Entity) other, entity));
    }

    private boolean ofDifferentTeams(Entity e1, Entity e2) {
        return !e1.getTeamName().equals(e2.getTeamName());
    }

    JSONObject takeSnapshot() {
        JSONObject snapshot = new JSONObject();
        JSONArray entities = new JSONArray();
        snapshot.put("entities", entities);
        JSONArray blocks = new JSONArray();
        snapshot.put("blocks", blocks);
        JSONArray dispensers = new JSONArray();
        snapshot.put("dispensers", dispensers);
        JSONArray taskArr = new JSONArray();
        snapshot.put("tasks", taskArr);
        for (GameObject o : gameObjects.values()) {
            if (o instanceof Entity) {
                JSONObject entity = new JSONObject();
                entity.put("id", o.getID());
                entity.put("x", ((Entity) o).getPosition().x);
                entity.put("y", ((Entity) o).getPosition().y);
                entity.put("name", ((Entity) o).getAgentName());
                entity.put("team", ((Entity) o).getTeamName());
                entities.put(entity);
            }
            else if (o instanceof Block) {
                JSONObject block = new JSONObject();
                block.put("x", ((Block) o).getPosition().x);
                block.put("y", ((Block) o).getPosition().y);
                block.put("type", ((Block) o).getBlockType());
                blocks.put(block);
            }
            else if (o instanceof Dispenser) {
                JSONObject dispenser = new JSONObject();
                dispenser.put("id", o.getID());
                dispenser.put("x", ((Dispenser) o).getPosition().x);
                dispenser.put("y", ((Dispenser) o).getPosition().y);
                dispenser.put("type", ((Dispenser) o).getBlockType());
                dispensers.put(dispenser);
            }
        }
        tasks.values().stream().filter(t -> !t.isCompleted()).forEach(t -> {
            JSONObject task  = new JSONObject();
            task.put("name", t.getName());
            task.put("deadline", t.getDeadline());
            task.put("reward", t.getReward());
            JSONArray requirementsArr = new JSONArray();
            task.put("requirements", requirementsArr);
            t.getRequirements().forEach((pos, type) -> {
                JSONObject requirement = new JSONObject();
                requirement.put("x", pos.x);
                requirement.put("y", pos.y);
                requirement.put("type", type);
                requirementsArr.put(requirement);
            });
            taskArr.put(task);
        });
        return snapshot;
    }

    JSONObject getResult() {
        JSONObject result =  new JSONObject();
        teams.values().forEach(t -> {
            JSONObject teamResult = new JSONObject();
            teamResult.put("score", t.getScore());
            result.put(t.getName(), teamResult);
        });
        return result;
    }

    boolean teleport(String entityName, Position targetPos) {
        Entity entity = getEntityByName(entityName);
        if (entity == null || targetPos == null) return false;
        if (grid.isUnblocked(targetPos)) {
            grid.moveWithoutAttachments(entity, targetPos);
            return true;
        }
        return false;
    }

    void setTerrain(Position p, Terrain terrain) {
        grid.setTerrain(p.x, p.y, terrain);
    }

    boolean attach(Position p1, Position p2) {
        Attachable a1 = getUniqueAttachable(p1);
        Attachable a2 = getUniqueAttachable(p2);
        if (a1 == null || a2 == null) return false;
        return grid.attach(a1, a2);
    }

    public class Area extends ArrayList<Position> {
        /**
         * Creates a new list containing all positions belonging to the
         * area around a given center within the given radius.
         */
        public Area(Position center, int radius) {
            for (var dx = -radius; dx <= radius; dx++) {
                var x = center.x + dx;
                var dy = radius - dx;
                for (var y = center.y - dy; y <= center.y + dy; y++) {
                    this.add(Position.of(x, y));
                }
            }
        }
    }
}