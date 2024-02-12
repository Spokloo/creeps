package com.epita.creeps;

import com.epita.creeps.given.exception.NoReportException;
import com.epita.creeps.given.extra.Cartographer;
import com.epita.creeps.given.json.Json;
import com.epita.creeps.given.vo.Resources;
import com.epita.creeps.given.vo.Tile;
import com.epita.creeps.given.vo.geometry.Point;
import com.epita.creeps.given.vo.parameter.FireParameter;
import com.epita.creeps.given.vo.parameter.MessageParameter;
import com.epita.creeps.given.vo.report.*;
import com.epita.creeps.given.vo.response.CommandResponse;
import com.epita.creeps.given.vo.response.InitResponse;
import com.epita.creeps.given.vo.response.StatisticsResponse;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class Program {
    private static String url;
    private static InitResponse response;
    private static String login;
    private static int tick;

    // --- Send commands ---
    private static HttpResponse<JsonNode> sendCommand(String command, String body, int duration, String citizenId) throws InterruptedException, UnirestException, ExecutionException {
        HttpResponse<JsonNode> cmd;
        if (body == null)
            cmd = Unirest.post(url + "/command/" + login + "/" + citizenId + "/" + command).asJson();
        else
            cmd = Unirest.post(url + "/command/" + login + "/" + citizenId + "/" + command).body(body).asJson();

        CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(duration, TimeUnit.MILLISECONDS)).get();
        return cmd;
    }

    private static HttpResponse<JsonNode> getReport(HttpResponse<JsonNode> cmd) throws UnirestException {
        CommandResponse cmdResp = Json.parse(cmd.getBody().toString(), CommandResponse.class);
        return Unirest.get(url + "/report/" + cmdResp.reportId).asJson();
    }

    private static HttpResponse<JsonNode> getStats() throws UnirestException {
        return Unirest.get(url + "/statistics").asJson();
    }

    // Handle Anti-Hector Technology
    private static MoveReport sendMove(String direction, String citizenId) throws UnirestException, InterruptedException, ExecutionException, NoReportException {
        HttpResponse<JsonNode> move = sendCommand("move:" + direction, null, response.costs.move.cast * tick, citizenId);
        MoveReport moveReport = Json.parseReport(getReport(move).getBody().toString());
        if (moveReport.status == Report.Status.ERROR)
            return moveReport;

        int ticksUntilHector = response.setup.gcTickRate - (moveReport.tick % response.setup.gcTickRate);
        if (ticksUntilHector < 20) {
            HttpResponse<JsonNode> moveBack = sendCommand("move:" + getOppositeDirection(direction), null, response.costs.move.cast * tick, citizenId);
            Json.parse(getReport(moveBack).getBody().toString(), MoveReport.class);

            CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor((long) ticksUntilHector * tick, TimeUnit.MILLISECONDS)).get();
            HttpResponse<JsonNode> moveForward = sendCommand("move:" + direction, null, response.costs.move.cast * tick, citizenId);
            moveReport = Json.parseReport(getReport(moveForward).getBody().toString());
        }

        if (moveReport.status == Report.Status.ERROR)
            return moveReport;
        Cartographer.INSTANCE.register(moveReport);
        return moveReport;
    }

    private static ObserveReport sendObserve(String citizenId) throws UnirestException, InterruptedException, ExecutionException, NoReportException {
        HttpResponse<JsonNode> observe = sendCommand("observe", null, response.costs.observe.cast * tick, citizenId);
        ObserveReport observeReport = Json.parseReport(getReport(observe).getBody().toString());
        if (observeReport.status == Report.Status.ERROR)
            return observeReport;
        Cartographer.INSTANCE.register(observeReport);
        return observeReport;
    }

    private static GatherReport sendGather(String citizenId) throws UnirestException, InterruptedException, ExecutionException, NoReportException {
        HttpResponse<JsonNode> gather = sendCommand("gather", null, response.costs.gather.cast * tick, citizenId);
        GatherReport gatherReport = Json.parseReport(getReport(gather).getBody().toString());
        if (gatherReport.status == Report.Status.ERROR)
            return gatherReport;
        System.out.println(gatherReport.gathered + " " + gatherReport.resource);
        Cartographer.INSTANCE.register(gatherReport);
        return gatherReport;
    }

    private static BuildReport sendBuild(String building, int duration, String citizenId) throws UnirestException, InterruptedException, ExecutionException, NoReportException {
        HttpResponse<JsonNode> build = sendCommand("build:" + building, null, duration, citizenId);
        BuildReport buildReport = Json.parseReport(getReport(build).getBody().toString());
        if (buildReport.status == Report.Status.ERROR)
            return buildReport;
        Cartographer.INSTANCE.register(buildReport);
        return buildReport;
    }

    private static SpawnReport sendSpawn(String unit, String citizenId) throws UnirestException, InterruptedException, ExecutionException {
        HttpResponse<JsonNode> spawn = sendCommand("spawn:" + unit, null, response.costs.spawnTurret.cast * tick, citizenId);
        return Json.parse(getReport(spawn).getBody().toString(), SpawnReport.class);
    }

    private static FireReport sendFire(String unit, Point dest, int duration, String unitId) throws UnirestException, InterruptedException, ExecutionException {
        FireParameter fireParameter = new FireParameter(dest);
        String fireJson = Json.serialize(fireParameter);

        HttpResponse<JsonNode> fire = sendCommand("fire:" + unit, fireJson, duration, unitId);
        return Json.parse(getReport(fire).getBody().toString(), FireReport.class);
    }

    private static SendMessageReport sendMessage(String recipient, String msg, String citizenId) throws UnirestException, InterruptedException, ExecutionException {
        MessageParameter msgParameter = new MessageParameter(recipient, msg);
        String msgJson = Json.serialize(msgParameter);

        HttpResponse<JsonNode> message = sendCommand("message:send", msgJson, response.costs.sendMessage.cast * tick, citizenId);
        return Json.parse(getReport(message).getBody().toString(), SendMessageReport.class);
    }

    private static FetchMessageReport fetchMessage(String citizenId) throws UnirestException, InterruptedException, ExecutionException {
        HttpResponse<JsonNode> message = sendCommand("message:fetch", null, response.costs.fetchMessage.cast * tick, citizenId);
        return Json.parse(getReport(message).getBody().toString(), FetchMessageReport.class);
    }

    private static RefineReport sendRefine(String resource, String citizenId) throws UnirestException, ExecutionException, InterruptedException {
        HttpResponse<JsonNode> refine = sendCommand("refine:" + resource, null, response.costs.refineCopper.cast * tick, citizenId);
        return Json.parse(getReport(refine).getBody().toString(), RefineReport.class);
    }

    private static UnloadReport sendUnload(String citizenId) throws UnirestException, ExecutionException, InterruptedException {
        HttpResponse<JsonNode> unload = sendCommand("unload", null, response.costs.unload.cast * tick, citizenId);
        return Json.parse(getReport(unload).getBody().toString(), UnloadReport.class);
    }

    // --- Utility ---
    private static String getOppositeDirection(String direction) {
        if (direction.equals("up"))
            return "down";
        if (direction.equals("down"))
            return "up";
        if (direction.equals("left"))
            return "right";
        if (direction.equals("right"))
            return "left";
        return null;
    }

    private static Point getClosestTileType(Point unitPosition, Tile tileType) {
        Stream<Point> points = Cartographer.INSTANCE.requestOfType(tileType);
        if (points == null)
            return null;
        return getClosestPoint(unitPosition, points);
    }

    private static Point getClosestPoint(Point src, Stream<Point> points) {
        double min = Double.MAX_VALUE;
        Point minPoint = null;

        if (points == null)
            return null;
        for (Point point : points.toList()) {
            double distance = Math.sqrt((src.x - point.x) * (src.x - point.x) + (src.y - point.y) * (src.y - point.y));
            if (distance < min) {
                min = distance;
                minPoint = point;
            }
        }

        return minPoint;
    }

    private static Report.Unit getClosestUnitPosition(Point src, List<Report.Unit> units) {
        double min = Double.MAX_VALUE;
        Report.Unit closestUnit = null;
        for (Report.Unit unit : units) {
            Point point = unit.position;
            double distance = Math.sqrt((src.x - point.x) * (src.x - point.x) + (src.y - point.y) * (src.y - point.y));
            if (distance < min) {
                if (closestUnit != null && closestUnit.player.startsWith(login))
                    continue;
                min = distance;
                closestUnit = unit;
            }
        }

        return closestUnit;
    }

    private static Point moveToPoint(Point src, Point dest, String citizenId, boolean buildRoads) throws UnirestException, InterruptedException, ExecutionException, NoReportException {
        if (src == null || dest == null)
            return src;

        int moveX = dest.x - src.x;
        int moveY = dest.y - src.y;
        Point newPos = src;

        while (moveX > 0) {
            newPos = buildRoad("right", citizenId, buildRoads);
            moveX--;
        }
        while (moveX < 0) {
            newPos = buildRoad("left", citizenId, buildRoads);
            moveX++;
        }

        while (moveY > 0) {
            newPos = buildRoad("up", citizenId, buildRoads);
            moveY--;
        }
        while (moveY < 0) {
            newPos = buildRoad("down", citizenId, buildRoads);
            moveY++;
        }

        return newPos;
    }

    private static Point buildRoad(String direction, String citizenId, boolean buildRoads) throws UnirestException, ExecutionException, InterruptedException, NoReportException {
        MoveReport moveReport = sendMove(direction, citizenId);
        Point unitPosition = moveReport.newPosition;

        Tile currTile = Cartographer.INSTANCE.requestTileType(unitPosition);
        if (!currTile.equals(Tile.Road) && !currTile.equals(Tile.Empty) && !currTile.equals(Tile.Water))
            sendGather(citizenId);

        if (buildRoads) {
            StatisticsResponse statisticsResponse = Json.parse(getStats().getBody().toString(), StatisticsResponse.class);
            for (StatisticsResponse.PlayerStatsResponse player : statisticsResponse.players) {
                if (player.name.startsWith(login)) {
                    if (player.resources.rock == 0) {
                        unitPosition = gatherClosestResource(citizenId, direction, Tile.Rock, moveReport.unitPosition, false);
                        unitPosition = moveToPoint(unitPosition, response.townHallCoordinates, citizenId, false);
                        UnloadReport unloadReport = sendUnload(citizenId);
                        System.out.println(unloadReport.creditedResources);
                    } else {
                        BuildReport buildReport = sendBuild("road", response.costs.buildRoad.cast * tick, citizenId);
                        if (buildReport.status == Report.Status.ERROR)
                            return unitPosition;
                    }

                    break;
                }
            }
        }

        return unitPosition;
    }

    private static Point gatherClosestResource(String citizenId, String direction, Tile tileType, Point returnTo, boolean buildRoads) throws UnirestException, InterruptedException, ExecutionException, NoReportException {
        // Get the closest tile
        Point unitPosition = null;
        int gatherTime = 3;
        for (int i = 0; i < gatherTime; i++) {
            Point closestTile = null;
            while (closestTile == null) {
                ObserveReport observeReport = sendObserve(citizenId);
                unitPosition = observeReport.unitPosition;
                closestTile = getClosestTileType(unitPosition, tileType);

                if (closestTile == null)
                    unitPosition = buildRoad(direction, citizenId, buildRoads);
            }

            // Move to the closest tile
            unitPosition = moveToPoint(unitPosition, closestTile, citizenId, buildRoads);

            // Gather maximum resource from tile
            GatherReport gatherReport = sendGather(citizenId);
            while (gatherReport.resourcesLeft > 0)
                gatherReport = sendGather(citizenId);
        }

        unitPosition = moveToPoint(unitPosition, returnTo, citizenId, buildRoads);
        if (unitPosition.equals(response.townHallCoordinates)) {
            UnloadReport unloadReport = sendUnload(citizenId);
            System.out.println(unloadReport.creditedResources);
        }

        return unitPosition;
    }

    private static void spawnAutoTurret(String citizenId) throws UnirestException, NoReportException, ExecutionException, InterruptedException {
        SpawnReport spawnReport = sendSpawn("turret", citizenId);
        ObserveReport observeReport = sendObserve(citizenId);

        for (int i = 0; i < 10000; i++) {
            for (Report.Unit unit : observeReport.units) {
                if (unit.player.startsWith(login))
                    continue;
                sendFire("turret", unit.position, response.costs.fireTurret.cast * tick, spawnReport.spawnedUnitId);
            }
        }
    }

    /*
    private static Point getClosestUnit(String citizenId, String direction) throws UnirestException, InterruptedException, ExecutionException {
        // Get the closest unit
        Point unitPosition = null;
        Report.Unit closestUnit = null;
        while (closestUnit == null) {
            ObserveReport observeReport = sendObserve(citizenId);
            unitPosition = observeReport.unitPosition;
            closestUnit = getClosestUnitPosition(unitPosition, observeReport.units);

            if (closestUnit == null)
                unitPosition = buildRoad(direction, citizenId);
        }

        // Move to the closest unit
        unitPosition = moveToPoint(unitPosition, closestUnit.position, citizenId);

        // Message unit
        sendMessage(closestUnit.player, "coucou owo", citizenId);

        unitPosition = moveToPoint(unitPosition, response.townHallCoordinates, citizenId);
        return unitPosition;
    }
    */

    private static void sendMessageToClosestUnit(String citizenId) throws UnirestException, InterruptedException, ExecutionException, NoReportException {
        // Get the closest unit
        Report.Unit closestUnit = null;
        while (closestUnit == null) {
            ObserveReport observeReport = sendObserve(citizenId);
            closestUnit = getClosestUnitPosition(observeReport.unitPosition, observeReport.units);
        }

        // Message unit
        sendMessage(closestUnit.player, "coucou owo", citizenId);
    }

    public static void main(String[] args) throws UnirestException, IOException, InterruptedException, ExecutionException {
        url = "http://" + args[0] + ":" + args[1];
        login = args[2];

        // Connect to server
        HttpResponse<JsonNode> connect = Unirest.post(url + "/init/" + login).asJson();
        response = Json.parse(connect.getBody().toString(), InitResponse.class);
        tick = (int) (1000 / response.setup.ticksPerSeconds);
        System.out.println(response);

        List<String> citizensId = new ArrayList<>();
        citizensId.add(response.citizen1Id);
        citizensId.add(response.citizen2Id);

        List<String> directions = List.of("up", "down", "left", "right");
        Random random = new Random();
        Thread gatherWood = new Thread(() -> {
            try {
                /*
                for (int i = 0; i < 2000; i++) {
                    StatisticsResponse statisticsResponse = Json.parse(getStats().getBody().toString(), StatisticsResponse.class);
                    for (StatisticsResponse.PlayerStatsResponse player : statisticsResponse.players) {
                        if (player.name.equals(response.login))
                            continue;
                        sendMessage(player.name, "coucou owo", response.citizen2Id);
                    }
                }*/

                // Gather the closest tile
                /*
                MoveReport moveReport = sendMove("left", response.citizen1Id);
                CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(8000, TimeUnit.MILLISECONDS)).get();
                sendBuild("road", response.costs.buildRoad.cast * tick, response.citizen1Id);
                Point unitPosition = moveReport.newPosition;

                int wood = 0;
                int rock = 0;
                while (wood < 15) {
                    StatisticsResponse statisticsResponse = Json.parse(getStats().getBody().toString(), StatisticsResponse.class);
                    for (StatisticsResponse.PlayerStatsResponse player : statisticsResponse.players) {
                        if (player.name.startsWith(login)) {
                            wood = player.resources.wood;
                            if (wood >= 15)
                                break;
                            System.out.println(player.resources + " FIRST CITIZEN - WOOD: " + wood);
                            unitPosition = gatherClosestResource(response.citizen1Id, directions.get(random.nextInt(directions.size())), Tile.Wood, response.townHallCoordinates, true);
                        }
                    }
                }

                while (rock < 25) {
                    StatisticsResponse statisticsResponse = Json.parse(getStats().getBody().toString(), StatisticsResponse.class);
                    for (StatisticsResponse.PlayerStatsResponse player : statisticsResponse.players) {
                        if (player.name.startsWith(login)) {
                            rock = player.resources.rock;
                            if (rock >= 25)
                                break;
                            System.out.println(player.resources + " FIRST CITIZEN - ROCK: " + rock);
                            unitPosition = gatherClosestResource(response.citizen1Id, directions.get(random.nextInt(directions.size())), Tile.Rock, response.townHallCoordinates, true);
                        }
                    }
                }

                moveToPoint(unitPosition, response.townHallCoordinates, response.citizen1Id, false);
            */
                for (int i = 0; i < 10000; i++) {
                    MoveReport moveReport = sendMove("left", response.citizen1Id);
                    Point closestTile = getClosestTileType(moveReport.newPosition, Tile.TownHall);
                    if (closestTile != null && !closestTile.equals(response.townHallCoordinates))
                        moveToPoint(moveReport.newPosition, closestTile, response.citizen1Id, false);
                }
            } catch (UnirestException | ExecutionException | InterruptedException | NoReportException e) {
                throw new RuntimeException(e);
            }
        });
        gatherWood.start();

        Thread spawnHouse = new Thread(() -> {
            try {
                /*
                for (int i = 0; i < 2000; i++) {
                    StatisticsResponse statisticsResponse = Json.parse(getStats().getBody().toString(), StatisticsResponse.class);
                    for (StatisticsResponse.PlayerStatsResponse player : statisticsResponse.players) {
                        if (player.name.equals(response.login))
                            continue;
                        sendMessage(player.name, "coucou owo", response.citizen2Id);
                    }
                }
                */

                /*
                sendMove("right", response.citizen2Id);
                BuildReport buildReport = sendBuild("household", response.costs.buildHousehold.cast * tick, response.citizen2Id);
                citizensId.add(buildReport.spawnedCitizen1Id);
                citizensId.add(buildReport.spawnedCitizen2Id);

                Thread gatherFood = new Thread(() -> {
                    try {
                        String newCitizen1Id = citizensId.get(2);

                        MoveReport moveReport = sendMove("up", newCitizen1Id);
                        CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(10000, TimeUnit.MILLISECONDS)).get();
                        sendBuild("road", response.costs.buildRoad.cast * tick, newCitizen1Id);
                        Point unitPosition = moveReport.newPosition;
                        for (int i = 0; i < 100; i++) {
                            unitPosition = gatherClosestResource(newCitizen1Id, directions.get(random.nextInt(directions.size())), Tile.Rock, response.townHallCoordinates, true);
                        }

                        moveToPoint(unitPosition, response.townHallCoordinates, newCitizen1Id, false);
                    } catch (UnirestException | NoReportException | ExecutionException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
                gatherFood.start();

                Thread defend = new Thread(() -> {
                    String newCitizen2Id = citizensId.get(3);
                    try {
                        sendMove("down", newCitizen2Id);
                        sendBuild("smeltery", response.costs.buildSmeltery.cast * tick, newCitizen2Id);
                        for (int i = 0; i < 10000; i++) {
                            StatisticsResponse statisticsResponse = Json.parse(getStats().getBody().toString(), StatisticsResponse.class);
                            for (StatisticsResponse.PlayerStatsResponse player : statisticsResponse.players) {
                                if (player.name.startsWith(login)) {
                                    if (player.resources.rock > 10)
                                        sendRefine("copper", newCitizen2Id);
                                }
                            }
                        }

                        //sendBuild("road", response.costs.buildRoad.cast * tick, newCitizen2Id);
                        //spawnAutoTurret(newCitizen2Id);
                    } catch (UnirestException | InterruptedException | ExecutionException | NoReportException e) {
                        throw new RuntimeException(e);
                    }
                });
                defend.start();

                Thread gatherRock = new Thread(() -> {
                    try {
                        MoveReport moveReport = sendMove("right", response.citizen2Id);
                        CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(10000, TimeUnit.MILLISECONDS)).get();
                        sendBuild("road", response.costs.buildRoad.cast * tick, response.citizen2Id);
                        Point unitPosition = moveReport.newPosition;

                        for (int i = 0; i < 100; i++) {
                            unitPosition = gatherClosestResource(response.citizen2Id, directions.get(random.nextInt(directions.size())), Tile.Rock, response.townHallCoordinates, true);
                        }

                        moveToPoint(unitPosition, response.townHallCoordinates, response.citizen2Id, false);
                    } catch (UnirestException | InterruptedException | ExecutionException | NoReportException e) {
                        throw new RuntimeException(e);
                    }
                });
                gatherRock.start();

                gatherFood.join();
                defend.join();
                gatherRock.join();
                */

                /*
                sendMove("right", response.citizen2Id);
                sendBuild("sawmill", response.costs.buildSawmill.cast * tick, response.citizen2Id);
                for (int i = 0; i < 10000; i++) {
                    StatisticsResponse statisticsResponse = Json.parse(getStats().getBody().toString(), StatisticsResponse.class);
                    for (StatisticsResponse.PlayerStatsResponse player : statisticsResponse.players) {
                        if (player.name.startsWith(login)) {
                            System.out.println(player.resources);
                            if (player.resources.wood > 15 && player.resources.rock > 25) {
                                sendMove("down", response.citizen2Id);
                                sendBuild("smeltery", response.costs.buildSmeltery.cast * tick, response.citizen2Id);
                            }
                        }
                    }

                    CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(1000, TimeUnit.MILLISECONDS)).get();
                }
                */
                /*
                for (int i = 0; i < 100; i++) {
                    gatherClosestResource(response.citizen2Id, directions.get(random.nextInt(directions.size())), Tile.Rock, response.townHallCoordinates, true);
                }
                */
                for (int i = 0; i < 10000; i++) {
                    MoveReport moveReport = sendMove("right", response.citizen2Id);
                    Point closestTile = getClosestTileType(moveReport.newPosition, Tile.TownHall);
                    if (closestTile != null && !closestTile.equals(response.townHallCoordinates))
                        moveToPoint(moveReport.newPosition, closestTile, response.citizen2Id, false);
                }
            } catch (InterruptedException | UnirestException | NoReportException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        });
        spawnHouse.start();

        gatherWood.join();
        spawnHouse.join();

        StatisticsResponse statisticsResponse = Json.parse(getStats().getBody().toString(), StatisticsResponse.class);
        System.out.println(statisticsResponse.players.stream().filter(p -> p.name.startsWith(login)).toList());
    }
}
