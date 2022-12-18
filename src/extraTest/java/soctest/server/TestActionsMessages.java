/**
 * Java Settlers - An online multiplayer version of the game Settlers of Catan
 * This file Copyright (C) 2020-2022 Jeremy D Monin <jeremy@nand.net>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * The maintainer of this program can be reached at jsettlers@nand.net
 **/
package soctest.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Vector;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

import soc.baseclient.SOCDisplaylessPlayerClient;  // for javadocs only
import soc.extra.server.GameEventLog;
import soc.extra.server.GameEventLog.EventEntry;
import soc.extra.server.RecordingSOCServer;
import soc.game.GameAction;
import soc.game.SOCBoard;
import soc.game.SOCBoardLarge;
import soc.game.SOCCity;
import soc.game.SOCDevCardConstants;
import soc.game.SOCGame;
import soc.game.SOCInventory;
import soc.game.SOCMoveRobberResult;
import soc.game.SOCPlayer;
import soc.game.SOCPlayingPiece;
import soc.game.SOCResourceConstants;
import soc.game.SOCResourceSet;
import soc.game.SOCRoad;
import soc.game.SOCRoutePiece;
import soc.game.SOCSettlement;
import soc.game.SOCShip;
import soc.game.SOCTradeOffer;
import soc.message.SOCChoosePlayer;
import soc.message.SOCNewGameWithOptions;
import soc.server.SOCGameHandler;
import soc.server.SOCServer;
import soc.server.savegame.SavedGameModel;
import soc.util.Version;
import soctest.server.TestRecorder.StartedTestGameObjects;
import soctest.server.savegame.TestLoadgame;

/**
 * Extra testing to cover all core game actions and their messages, as recorded by {@link RecordingSOCServer}.
 * Expands coverage past the basic unit tests done by {@link TestRecorder}.
 * @since 2.5.00
 */
public class TestActionsMessages
{
    private static RecordingSOCServer srv;

    @BeforeClass
    public static void startStringportServer()
    {
        SOCGameHandler.DESTROY_BOT_ONLY_GAMES_WHEN_OVER = false;  // keep games around, to check asserts

        srv = new RecordingSOCServer();
        srv.setPriority(5);  // same as in SOCServer.main
        srv.start();

        // wait for startup
        try
        {
            Thread.sleep(500);
        }
        catch (InterruptedException e) {}
    }

    @AfterClass
    public static void shutdownServer()
    {
        // for clearer sequences in System.out, wait for other threads' prints to complete
        try
        {
            Thread.sleep(250);
        }
        catch (InterruptedException e) {}

        System.out.flush();
        System.out.println();
        srv.stopServer();
    }

    /**
     * Re-run unit test {@link TestRecorder#testBasics_Loadgame(SOCServer)},
     * to rule out test setup problems if other tests fail.
     */
    @Test
    public void testBasics_Loadgame()
        throws IOException
    {
        assertNotNull(srv);

        TestRecorder.testBasics_Loadgame(srv);
    }

    /**
     * Test saving and loading {@link GameEventLog} files.
     * Re-runs unit test {@link TestRecorder#testLoadAndBasicSequences(RecordingSOCServer, String, List, boolean)},
     * saves and loads the resulting event entries:
     * Calls {@link GameEventLog} constructor, {@link GameEventLog#save(File, String, boolean, boolean)},
     * {@link GameEventLog#load(File, boolean, int)}.
     *
     * @throws IOException if game artifact file can't be loaded, or soclog can't be saved or loaded
     * @throws ParseException if soclog fails parsing during load
     * @throws NoSuchElementException if soclog is missing a header during load
     */
    @Test
    public void testSaveLoadBasicSequences()
        throws IOException, NoSuchElementException, ParseException
    {
        // unique client nickname, in case tests run in parallel
        final String CLIENT_NAME = "testSaveLoadBasic";

        ArrayList<EventEntry> allRecords = new ArrayList<>();
        final SOCGame ga = TestRecorder.testLoadAndBasicSequences(srv, CLIENT_NAME, allRecords, false);
        final int logSize = allRecords.size();
        assertNotEquals(0, logSize);

        final GameEventLog savedLog = new GameEventLog(ga, false);
        savedLog.entries.addAll(allRecords);
        assertEquals(logSize, savedLog.entries.size());

        final Path tmpSoclogFilePath = Files.createTempFile(CLIENT_NAME, ".soclog.tmp");
        try
        {
            // save:
            final File tmpSoclogFile = tmpSoclogFilePath.toFile();
            savedLog.save(tmpSoclogFile.getParentFile(), tmpSoclogFile.getName(), true, false);
            final long size = tmpSoclogFile.length();
            assertTrue("tmp soclog size >= 1k", size >= 1024);
            srv.destroyGameAndBroadcast(ga.getName(), null);

            // load:
            final GameEventLog loadedLog = GameEventLog.load(tmpSoclogFile, true, -1);
            assertNotNull(loadedLog);

            // compare:
            assertEquals("saved log size " + logSize + " == loaded size?", logSize, loadedLog.entries.size());
            assertEquals(ga.getName(), loadedLog.gameName);
            assertEquals(Version.versionNumber(), loadedLog.version);
            assertNotNull(loadedLog.optsStr);  // to compare in detail to ga.getGameOptions(), would need to parse/sort them
            for (int i = 0; i < logSize; ++i)
            {
                final GameEventLog.EventEntry eeSave = allRecords.get(i), eeLoad = loadedLog.entries.get(i);
                if ((eeSave.event instanceof SOCNewGameWithOptions)
                    && (eeLoad.event instanceof SOCNewGameWithOptions))
                    continue;  // skip; game opts string match is inexact (leading ',', etc)

                assertEquals("log index " + i, eeSave, eeLoad);
            }
        } finally {
            // cleanup
            Files.deleteIfExists(tmpSoclogFilePath);
        }
    }


    /**
     * Tests building pieces and moving ships.
     * Builds all piece types, except road without SOCBuildingRequest
     * (covered by {@link TestRecorder#testLoadAndBasicSequences()}).
     * @see #testUndoBuildAndMove()
     */
    @Test
    public void testBuildAndMove()
        throws IOException
    {
        assertNotNull(srv);

        for (boolean withBuildRequest : new boolean[]{false, true})
        {
            testOne_BuildAndMove(withBuildRequest, false, false);
            testOne_BuildAndMove(withBuildRequest, false, true);
            testOne_BuildAndMove(withBuildRequest, true, false);
            testOne_BuildAndMove(withBuildRequest, true, true);
        }
    }

    private void testOne_BuildAndMove
        (final boolean withBuildRequest, final boolean clientAsRobot, final boolean othersAsRobot)
        throws IOException
    {
        // unique client nickname, in case tests run in parallel
        final String CLIENT_NAME = "testBuild_"
            + (withBuildRequest ? "WB_" : "NB_") + (clientAsRobot ? 'r' : 'h') + (othersAsRobot ? "_r" : "_h");
        final int CLIENT_PN = 3;

        final StartedTestGameObjects objs =
            TestRecorder.connectLoadJoinResumeGame
                (srv, CLIENT_NAME, null, 0, null, true, 0, clientAsRobot, othersAsRobot);
        final DisplaylessTesterClient tcli = objs.tcli;
        // final SavedGameModel sgm = objs.sgm;
        final SOCGame ga = objs.gameAtServer, gaAtCli = tcli.getGame(ga.getName());
        final SOCBoardLarge board = (SOCBoardLarge) objs.board;
        final SOCPlayer cliPl = objs.clientPlayer;
        final Vector<EventEntry> records = objs.records;
        assertEquals(CLIENT_PN, cliPl.getPlayerNumber());

        /* build road */

        records.clear();
        StringBuilder comparesRoad;
        if (withBuildRequest)
        {
            comparesRoad = TestRecorder.buildRoadSequence(tcli, ga, cliPl, records, true);

            // don't deplete resources, since this sequence isn't unconditionally tested
            cliPl.getResources().setAmounts(new SOCResourceSet(3, 3, 3, 4, 4, 0));
        } else {
            // already covered by another unit test in TestRecorder

            comparesRoad = null;
        }

        /* build settlement (on main island) */

        records.clear();
        final int SETTLEMENT_NODE = 0x60a;
        assertNull(board.settlementAtNode(SETTLEMENT_NODE));
        assertEquals(3, cliPl.getNumPieces(SOCPlayingPiece.SETTLEMENT));
        assertEquals(2, cliPl.getPublicVP());
        assertArrayEquals(new int[]{3, 3, 3, 4, 4}, cliPl.getResources().getAmounts(false));
        if (withBuildRequest)
        {
            tcli.buildRequest(ga, SOCPlayingPiece.SETTLEMENT);

            try { Thread.sleep(60); }
            catch(InterruptedException e) {}
            assertEquals(SOCGame.PLACING_SETTLEMENT, ga.getGameState());
            assertArrayEquals(new int[]{2, 3, 2, 3, 3}, cliPl.getResources().getAmounts(false));
        }
        tcli.putPiece(ga, new SOCSettlement(cliPl, SETTLEMENT_NODE, board));

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertNotNull("settlement built", board.settlementAtNode(SETTLEMENT_NODE));
        assertEquals(2, cliPl.getNumPieces(SOCPlayingPiece.SETTLEMENT));
        assertEquals(3, cliPl.getPublicVP());
        assertArrayEquals(new int[]{2, 3, 2, 3, 3}, cliPl.getResources().getAmounts(false));

        GameAction act = ga.getLastAction();
        assertNotNull(act);
        assertEquals(GameAction.ActionType.BUILD_PIECE, act.actType);
        assertEquals(SOCPlayingPiece.SETTLEMENT, act.param1);
        assertEquals(SETTLEMENT_NODE, act.param2);
        assertEquals(CLIENT_PN, act.param3);
        {
            List<GameAction.Effect> effects = act.effects;
            assertNotNull(effects);
            assertEquals(1, effects.size());

            GameAction.Effect e = effects.get(0);
            assertEquals(GameAction.EffectType.DEDUCT_COST_FROM_PLAYER, e.eType);
            assertNull(e.params);
        }

        StringBuilder comparesSettle = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCPlayerElements:", "|playerNum=3|actionType=LOSE|e1=1,e3=1,e4=1,e5=1"},
                ((withBuildRequest) ? new String[]{"all:SOCGameState:", "|state=31"} : null),
                {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " built a settlement."},
                {"all:SOCPutPiece:", "|playerNumber=3|pieceType=1|coord=60a"},
                {"all:SOCGameState:", "|state=20"}
            }, false);

        /* upgrade that settlement to city */

        records.clear();
        assertEquals(4, cliPl.getNumPieces(SOCPlayingPiece.CITY));
        assertEquals(2, cliPl.getNumPieces(SOCPlayingPiece.SETTLEMENT));
        if (withBuildRequest)
        {
            tcli.buildRequest(ga, SOCPlayingPiece.CITY);

            try { Thread.sleep(60); }
            catch(InterruptedException e) {}
            assertEquals(SOCGame.PLACING_CITY, ga.getGameState());
            assertArrayEquals(new int[]{2, 0, 2, 1, 3}, cliPl.getResources().getAmounts(false));
        }
        tcli.putPiece(ga, new SOCCity(cliPl, SETTLEMENT_NODE, board));

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertTrue("city built", board.settlementAtNode(SETTLEMENT_NODE) instanceof SOCCity);
        assertEquals(3, cliPl.getNumPieces(SOCPlayingPiece.CITY));
        assertEquals(3, cliPl.getNumPieces(SOCPlayingPiece.SETTLEMENT));
        assertEquals(4, cliPl.getPublicVP());
        assertArrayEquals(new int[]{2, 0, 2, 1, 3}, cliPl.getResources().getAmounts(false));

        act = ga.getLastAction();
        assertNotNull(act);
        assertEquals(GameAction.ActionType.BUILD_PIECE, act.actType);
        assertEquals(SOCPlayingPiece.CITY, act.param1);
        assertEquals(SETTLEMENT_NODE, act.param2);
        assertEquals(CLIENT_PN, act.param3);
        {
            List<GameAction.Effect> effects = act.effects;
            assertNotNull(effects);
            assertEquals(1, effects.size());

            GameAction.Effect e = effects.get(0);
            assertEquals(GameAction.EffectType.DEDUCT_COST_FROM_PLAYER, e.eType);
            assertNull(e.params);
        }

        StringBuilder comparesCity = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCPlayerElements:", "|playerNum=3|actionType=LOSE|e2=3,e4=2"},
                ((withBuildRequest) ? new String[]{"all:SOCGameState:", "|state=32"} : null),
                {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " built a city."},
                {"all:SOCPutPiece:", "|playerNumber=3|pieceType=2|coord=60a"},
                {"all:SOCGameState:", "|state=20"}
            }, false);

        /* build ship */

        records.clear();
        assertEquals(11, cliPl.getNumPieces(SOCPlayingPiece.SHIP));
        assertTrue(board.roadOrShipAtEdge(0xd05) instanceof SOCShip);
        final int SHIP_EDGE = 0xe05;
        assertNull(board.roadOrShipAtEdge(SHIP_EDGE));
        List<Integer> shipsThisTurnListAtCli = gaAtCli.getShipsPlacedThisTurn();
            // loaded from message-seqs.game.json, sent to client player during join
        assertNotNull(shipsThisTurnListAtCli);
        assertEquals(1, shipsThisTurnListAtCli.size());
        assertTrue(shipsThisTurnListAtCli.contains(Integer.valueOf(0xd05)));
        if (withBuildRequest)
        {
            tcli.buildRequest(ga, SOCPlayingPiece.SHIP);

            try { Thread.sleep(60); }
            catch(InterruptedException e) {}
            assertEquals(SOCGame.PLACING_SHIP, ga.getGameState());
            assertArrayEquals(new int[]{2, 0, 1, 1, 2}, cliPl.getResources().getAmounts(false));
        }
        tcli.putPiece(ga, new SOCShip(cliPl, SHIP_EDGE, board));

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertTrue("ship built", board.roadOrShipAtEdge(SHIP_EDGE) instanceof SOCShip);
        assertEquals(10, cliPl.getNumPieces(SOCPlayingPiece.SHIP));
        assertNull(gaAtCli.canMoveShip(CLIENT_PN, SHIP_EDGE));
        assertArrayEquals(new int[]{2, 0, 1, 1, 2}, cliPl.getResources().getAmounts(false));
        shipsThisTurnListAtCli = gaAtCli.getShipsPlacedThisTurn();
        assertEquals(2, shipsThisTurnListAtCli.size());
        assertTrue(shipsThisTurnListAtCli.contains(Integer.valueOf(SHIP_EDGE)));

        act = ga.getLastAction();
        assertNotNull(act);
        assertEquals(GameAction.ActionType.BUILD_PIECE, act.actType);
        assertEquals(SOCPlayingPiece.SHIP, act.param1);
        assertEquals(SHIP_EDGE, act.param2);
        assertEquals(CLIENT_PN, act.param3);
        {
            List<GameAction.Effect> effects = act.effects;
            assertNotNull(effects);
            assertEquals(1, effects.size());

            GameAction.Effect e = effects.get(0);
            assertEquals(GameAction.EffectType.DEDUCT_COST_FROM_PLAYER, e.eType);
            assertNull(e.params);
        }

        StringBuilder comparesShipBuild = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCPlayerElements:", "|playerNum=3|actionType=LOSE|e3=1,e5=1"},
                ((withBuildRequest) ? new String[]{"all:SOCGameState:", "|state=35"} : null),
                {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " built a ship."},
                {"all:SOCPutPiece:", "|playerNumber=3|pieceType=3|coord=e05"},
                {"all:SOCGameState:", "|state=20"}
            }, false);

        /* move a different ship */

        records.clear();
        final int MOVESHIP_EDGE_FROM = 0xc06,
            MOVESHIP_EDGE_TO = 0xf06;
        assertTrue("moving ship from here", board.roadOrShipAtEdge(MOVESHIP_EDGE_FROM) instanceof SOCShip);
        assertNull("no ship here yet", board.roadOrShipAtEdge(MOVESHIP_EDGE_TO));
        assertNotNull(gaAtCli.canMoveShip(CLIENT_PN, MOVESHIP_EDGE_FROM, MOVESHIP_EDGE_TO));
        tcli.movePieceRequest(ga, CLIENT_PN, SOCPlayingPiece.SHIP, MOVESHIP_EDGE_FROM, MOVESHIP_EDGE_TO);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertTrue("ship moved", board.roadOrShipAtEdge(MOVESHIP_EDGE_TO) instanceof SOCShip);
        assertNull("ship moved from here", board.roadOrShipAtEdge(MOVESHIP_EDGE_FROM));
        assertEquals(4, cliPl.getPublicVP());  // unchanged by this move
        shipsThisTurnListAtCli = gaAtCli.getShipsPlacedThisTurn();
        assertEquals(3, shipsThisTurnListAtCli.size());
        assertTrue(shipsThisTurnListAtCli.contains(Integer.valueOf(MOVESHIP_EDGE_TO)));

        act = ga.getLastAction();
        assertNotNull(act);
        assertEquals(GameAction.ActionType.MOVE_PIECE, act.actType);
        assertEquals(SOCPlayingPiece.SHIP, act.param1);
        assertEquals(MOVESHIP_EDGE_FROM, act.param2);
        assertEquals(MOVESHIP_EDGE_TO, act.param3);
        assertNull(act.effects);

        StringBuilder comparesShipMove = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCMovePiece:", "|pn=3|pieceType=3|fromCoord=c06|toCoord=f06"}
            }, false);

        /* build settlement (on small island) */

        records.clear();
        final int SETTLEMENT_ISL_NODE = 0x1006;
        assertNull(board.settlementAtNode(SETTLEMENT_ISL_NODE));
        assertEquals(3, cliPl.getNumPieces(SOCPlayingPiece.SETTLEMENT));
        tcli.putPiece(ga, new SOCSettlement(cliPl, SETTLEMENT_ISL_NODE, board));

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertNotNull("settlement built", board.settlementAtNode(SETTLEMENT_ISL_NODE));
        assertEquals(2, cliPl.getNumPieces(SOCPlayingPiece.SETTLEMENT));
        assertEquals(5, cliPl.getPublicVP());
        assertArrayEquals(new int[]{1, 0, 0, 0, 1}, cliPl.getResources().getAmounts(false));

        StringBuilder comparesSettleIsl = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCPlayerElements:", "|playerNum=3|actionType=LOSE|e1=1,e3=1,e4=1,e5=1"},
                {"all:SOCGameServerText:", "|text="+ CLIENT_NAME + " built a settlement."},
                {"all:SOCPutPiece:", "|playerNumber=3|pieceType=1|coord=1006"},
                {"all:SOCGameState:", "|state=20"}
            }, false);

        /* leave game, consolidate results */

        srv.destroyGameAndBroadcast(ga.getName(), null);
        tcli.destroy();

        StringBuilder compares = new StringBuilder();
        if (comparesRoad != null)
        {
            compares.append("Build road: Message mismatch: ");
            compares.append(comparesRoad);
        }
        if (comparesSettle != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Build settlement: Message mismatch: ");
            compares.append(comparesSettle);
        }
        if (comparesCity != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Build city: Message mismatch: ");
            compares.append(comparesCity);
        }
        if (comparesShipBuild != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Build ship: Message mismatch: ");
            compares.append(comparesShipBuild);
        }
        if (comparesShipMove != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Move ship: Message mismatch: ");
            compares.append(comparesShipMove);
        }
        if (comparesSettleIsl != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Build settlement on island: Message mismatch: ");
            compares.append(comparesSettleIsl);
        }

        if (compares.length() > 0)
        {
            compares.insert(0, "For test " + CLIENT_NAME + ": ");
            System.err.println(compares);
            fail(compares.toString());
        }
    }

    /**
     * Tests undo for building pieces and moving ships, including Longest Route changes,
     * with {@code reletest-longest-joinships.game.json}.
     * Builds and undoes 1 of each piece type;
     * {@link #testBuildAndMove()} has more extensive tests for building.
     * @since 2.7.00
     */
    @Test
    public void testUndoBuildAndMove()
        throws IOException
    {
        assertNotNull(srv);

        testOne_UndoBuildAndMove(false, false);
        testOne_UndoBuildAndMove(false, true);
        testOne_UndoBuildAndMove(true, false);
        testOne_UndoBuildAndMove(true, true);
    }

    private void testOne_UndoBuildAndMove
        (final boolean clientAsRobot, final boolean othersAsRobot)
        throws IOException
    {
        // unique client nickname, in case tests run in parallel
        final String CLIENT_NAME = "testUndoBAM_"
            + (clientAsRobot ? 'r' : 'h') + (othersAsRobot ? "_r" : "_h");
        final int CLIENT_PN = 3, OTHER_PN = 2;

        final SavedGameModel sgm = soctest.server.savegame.TestLoadgame.load
            ("reletest-longest-joinships.game.json", srv);
        assertNotNull(sgm);
        final StartedTestGameObjects objs =
            TestRecorder.connectLoadJoinResumeGame
                (srv, CLIENT_NAME, null, 0, sgm, true, 0, clientAsRobot, othersAsRobot);
        final DisplaylessTesterClient tcli = objs.tcli;
        final SOCGame ga = objs.gameAtServer;
        final SOCPlayer cliPl = objs.clientPlayer;
        final Vector<EventEntry> records = objs.records;
        assertEquals(CLIENT_PN, cliPl.getPlayerNumber());
        assertFalse(ga.isSeatVacant(OTHER_PN));

        /* settlement to join roads + ships for longest route */
        final int SETTLEMENT_NODE = 0x80b;
        final int[] SHIP_ROUTE_BECOMES_CLOSED = {0xc08, 0xc09, 0xb0a, 0xa0a, 0x90b},
            SHIP_ROUTE_REMAINS_OPEN = {0xc0a, 0xc0b};
        testOne_UndoBuildAndMove_buildAndUndoPiece
            (SOCPlayingPiece.SETTLEMENT, SETTLEMENT_NODE, 0, 3,
             SHIP_ROUTE_BECOMES_CLOSED, SHIP_ROUTE_REMAINS_OPEN, objs, clientAsRobot, othersAsRobot);
        StringBuilder comparesSettle = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCUndoPutPiece:", "|playerNumber=3|pieceType=1|coord=80b"},
                {"all:SOCPlayerElements:", "|playerNum=3|actionType=GAIN|e1=1,e3=1,e4=1,e5=1"},
                {"all:SOCLongestRoad:", "|playerNumber=2"},
            }, false);

        final int ROAD_EDGE = 0xc07;
        testOne_UndoBuildAndMove_buildAndUndoPiece
            (SOCPlayingPiece.ROAD, ROAD_EDGE, 0, 13, null, null, objs, clientAsRobot, othersAsRobot);
        StringBuilder comparesRoad = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCUndoPutPiece:", "|playerNumber=3|pieceType=0|coord=c07"},
                {"all:SOCPlayerElements:", "|playerNum=3|actionType=GAIN|e1=1,e5=1"},
                {"all:SOCLongestRoad:", "|playerNumber=2"},
            }, false);

        final int SHIP_EDGE_EAST = 0x80b;
        testOne_UndoBuildAndMove_buildAndUndoPiece
            (SOCPlayingPiece.SHIP, SHIP_EDGE_EAST, 0, 8, null, null, objs, clientAsRobot, othersAsRobot);
        StringBuilder comparesShip = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCUndoPutPiece:", "|playerNumber=3|pieceType=3|coord=80b"},
                {"all:SOCPlayerElements:", "|playerNum=3|actionType=GAIN|e3=1,e5=1"},
                {"all:SOCLongestRoad:", "|playerNumber=2"},
            }, false);

        /* move ship & undo */
        final int SHIP_MOVE_FROM = 0xc0b;
        testOne_UndoBuildAndMove_buildAndUndoPiece
            (SOCPlayingPiece.SHIP, SHIP_EDGE_EAST, SHIP_MOVE_FROM, 8, null, null, objs, clientAsRobot, othersAsRobot);
        StringBuilder comparesShipMove = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCUndoPutPiece:", "|playerNumber=3|pieceType=3|coord=80b|movedFromCoord=c0b"},
                {"all:SOCLongestRoad:", "|playerNumber=2"},
            }, false);
        if (comparesShipMove == null)
        {
            /* move and undo same ship, to verify we've undone game data about ships moved/placed this turn */
            System.out.println("(Re-testing move & undo same ship)");
            testOne_UndoBuildAndMove_buildAndUndoPiece
                (SOCPlayingPiece.SHIP, SHIP_EDGE_EAST, SHIP_MOVE_FROM, 8, null, null, objs, clientAsRobot, othersAsRobot);
            comparesShipMove = TestRecorder.compareRecordsToExpected
                (records, new String[][]
                {
                    {"all:SOCUndoPutPiece:", "|playerNumber=3|pieceType=3|coord=80b|movedFromCoord=c0b"},
                    {"all:SOCLongestRoad:", "|playerNumber=2"},
                }, false);
            if (comparesShipMove != null)
                comparesShipMove.insert(0, "Test move & undo same ship:");
        }

        // TODO test close ship route by moving another ship
        // TODO implement reopen during undo

        /* leave game, consolidate results */

        srv.destroyGameAndBroadcast(ga.getName(), null);
        tcli.destroy();

        StringBuilder compares = new StringBuilder();
        if (comparesSettle != null)
        {
            compares.append("Undo build settlement: Message mismatch: ");
            compares.append(comparesSettle);
        }
        if (comparesRoad != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Undo build road: Message mismatch: ");
            compares.append(comparesRoad);
        }
        if (comparesShip != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Undo build ship: Message mismatch: ");
            compares.append(comparesShip);
        }
        if (comparesShipMove != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Undo move ship: Message mismatch: ");
            compares.append(comparesShipMove);
        }

        if (compares.length() > 0)
        {
            compares.insert(0, "For test " + CLIENT_NAME + ": ");
            System.err.println(compares);
            fail(compares.toString());
        }
    }

    /**
     * Build or move a piece which gains longest road for client player, then undo it to revert longest road,
     * for {@link #testOne_UndoBuildAndMove(boolean, boolean)}.
     * Calls {@code records.clear()} before sending undo request.
     * @param pieceType  Piece type to build and then undo: Must be ROAD, SETTLEMENT or SHIP
     * @param pieceCoord  Node or edge coordinate to build at or move to
     * @param movedFromCoord  Edge coordinate to move ship from, or 0 when building
     * @param startPieceCount  Player's expected amount of {@code pieceType} before building one
     * @param shipRouteBecomesClosed  Edges to check that {@link SOCShip#isClosed()} becomes true with placement, or null
     * @param shipRouteRemainsOpen  Edges to check that {@link SOCShip#isClosed()} remains false with placement, or null
     * @param objs  Game and client/server objects
     * @param clientAsRobot
     * @param othersAsRobot
     * @since 2.7.00
     */
    private void testOne_UndoBuildAndMove_buildAndUndoPiece
        (final int pieceType, final int pieceCoord, final int movedFromCoord, final int startPieceCount,
         final int[] shipRouteBecomesClosed, final int[] shipRouteRemainsOpen,
         final StartedTestGameObjects objs, final boolean clientAsRobot, final boolean othersAsRobot)
    {
        final int CLIENT_PN = 3, OTHER_PN = 2;
        final int[] PL_START_RES_ARR = {2, 0, 2, 1, 2};
        final int[] PL_NEXT_RES_ARR;  // use array so assert(..) will print details of what's unexpected
        if (movedFromCoord == 0)
        {
            SOCResourceSet res = new SOCResourceSet(PL_START_RES_ARR);
            res.subtract(SOCPlayingPiece.getResourcesToBuild(pieceType));
            PL_NEXT_RES_ARR = res.getAmounts(false);
        } else {
            PL_NEXT_RES_ARR = null;
        }
        final String testDesc =
            ((movedFromCoord != 0)
                ? "move ship from 0x"  + Integer.toHexString(movedFromCoord)
                : "place piecetype=" + pieceType)
            + " at 0x" + Integer.toHexString(pieceCoord);

        final DisplaylessTesterClient tcli = objs.tcli;
        final SOCGame ga = objs.gameAtServer, gaAtCli = tcli.getGame(ga.getName());
        final SOCBoardLarge board = (SOCBoardLarge) objs.board;
        final SOCPlayer cliPl = objs.clientPlayer, cliPlAtCli = gaAtCli.getPlayer(CLIENT_PN);
        final Vector<EventEntry> records = objs.records;
        assertEquals(CLIENT_PN, cliPl.getPlayerNumber());
        assertFalse(ga.isSeatVacant(OTHER_PN));

        // Pre-check ships, if any:
        if (shipRouteBecomesClosed != null)
            for (int edge : shipRouteBecomesClosed)
                assertFalse(testDesc + ": ship at 0x" + Integer.toHexString(edge) + " should be open",
                    ((SOCShip) board.roadOrShipAtEdge(edge)).isClosed());
        if (shipRouteRemainsOpen != null)
            for (int edge : shipRouteRemainsOpen)
                assertFalse(testDesc + ": ship at 0x" + Integer.toHexString(edge) + " should be open",
                    ((SOCShip) board.roadOrShipAtEdge(edge)).isClosed());

        // records.clear();
        if ((pieceType == SOCPlayingPiece.ROAD) || (pieceType == SOCPlayingPiece.SHIP))
            assertNull(testDesc, board.roadOrShipAtEdge(pieceCoord));
        else
            assertNull(testDesc, board.settlementAtNode(pieceCoord));
        assertEquals(testDesc, startPieceCount, cliPl.getNumPieces(pieceType));
        assertEquals(testDesc, 2, cliPl.getPublicVP());
        assertEquals(testDesc, OTHER_PN, ga.getPlayerWithLongestRoad().getPlayerNumber());
        assertEquals(testDesc, OTHER_PN, gaAtCli.getPlayerWithLongestRoad().getPlayerNumber());
        assertArrayEquals(testDesc, PL_START_RES_ARR, cliPl.getResources().getAmounts(false));
        assertArrayEquals(testDesc, PL_START_RES_ARR, cliPlAtCli.getResources().getAmounts(false));
        if (movedFromCoord == 0)
        {
            final SOCPlayingPiece pieceToPut;
            switch (pieceType)
            {
            case SOCPlayingPiece.ROAD:
                pieceToPut = new SOCRoad(cliPl, pieceCoord, board);
                break;
            case SOCPlayingPiece.SETTLEMENT:
                pieceToPut = new SOCSettlement(cliPl, pieceCoord, board);
                break;
            case SOCPlayingPiece.SHIP:
                pieceToPut = new SOCShip(cliPl, pieceCoord, board);
                break;
            default:
                fail(testDesc + ": unsupported pieceType for test");
                return;  // to satisfy compiler
            }
            tcli.putPiece(ga, pieceToPut);
        } else {
            tcli.movePieceRequest(ga, CLIENT_PN, SOCPlayingPiece.SHIP, movedFromCoord, pieceCoord);
        }

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        String pieceCheckDesc = testDesc + ((movedFromCoord == 0) ? ": built it" : ": moved it");
        if ((pieceType == SOCPlayingPiece.ROAD) || (pieceType == SOCPlayingPiece.SHIP))
            assertNotNull(pieceCheckDesc, board.roadOrShipAtEdge(pieceCoord));
        else
            assertNotNull(pieceCheckDesc, board.settlementAtNode(pieceCoord));
        if (movedFromCoord == 0)
            assertEquals(testDesc, startPieceCount - 1, cliPl.getNumPieces(pieceType));
        assertEquals(testDesc, (pieceType == SOCPlayingPiece.SETTLEMENT) ? 5 : 4, cliPl.getPublicVP());
        assertEquals(testDesc, CLIENT_PN, ga.getPlayerWithLongestRoad().getPlayerNumber());
        assertEquals(testDesc, CLIENT_PN, gaAtCli.getPlayerWithLongestRoad().getPlayerNumber());
        if (movedFromCoord == 0)
        {
            assertArrayEquals(testDesc, PL_NEXT_RES_ARR, cliPl.getResources().getAmounts(false));
            assertArrayEquals(testDesc, PL_NEXT_RES_ARR, cliPlAtCli.getResources().getAmounts(false));
        }
        if (shipRouteBecomesClosed != null)
            for (int edge : shipRouteBecomesClosed)
                assertTrue(testDesc + ": ship at 0x" + Integer.toHexString(edge) + " should be closed",
                    ((SOCShip) board.roadOrShipAtEdge(edge)).isClosed());
        if (shipRouteRemainsOpen != null)
            for (int edge : shipRouteRemainsOpen)
                assertFalse(testDesc + ": ship at 0x" + Integer.toHexString(edge) + " should still be open",
                    ((SOCShip) board.roadOrShipAtEdge(edge)).isClosed());

        GameAction act = ga.getLastAction();
        assertNotNull(testDesc, act);
        if (movedFromCoord == 0)
        {
            assertEquals(testDesc, GameAction.ActionType.BUILD_PIECE, act.actType);
            assertEquals(testDesc, pieceType, act.param1);
            assertEquals(testDesc, pieceCoord, act.param2);
            assertEquals(testDesc, CLIENT_PN, act.param3);
        } else {
            assertEquals(testDesc, GameAction.ActionType.MOVE_PIECE, act.actType);
            assertEquals(testDesc, pieceType, act.param1);
            assertEquals(testDesc, movedFromCoord, act.param2);
            assertEquals(testDesc, pieceCoord, act.param3);
        }
        {
            List<GameAction.Effect> effects = act.effects;
            assertNotNull(testDesc, effects);
            assertEquals(testDesc,
                ((movedFromCoord == 0) ? 2 : 1) + ((shipRouteBecomesClosed != null) ? 1 : 0),
                effects.size());

            GameAction.Effect e = effects.get(0);
            if (shipRouteBecomesClosed != null)
            {
                assertEquals(testDesc, GameAction.EffectType.CLOSE_SHIP_ROUTE, e.eType);
                assertNotNull(testDesc, e.params);
                assertEquals(testDesc, shipRouteBecomesClosed.length, e.params.length);
                for (int edge : shipRouteBecomesClosed)
                     assertTrue(Arrays.stream(e.params).anyMatch(i -> i == edge));
                e = effects.get(1);
            }
            if (movedFromCoord == 0)
            {
                assertEquals(testDesc, GameAction.EffectType.DEDUCT_COST_FROM_PLAYER, e.eType);
                assertNull(testDesc, e.params);
                e = effects.get(effects.size() - 1);
            }
            assertEquals(testDesc, GameAction.EffectType.CHANGE_LONGEST_ROAD_PLAYER, e.eType);
            assertArrayEquals(testDesc, new int[]{OTHER_PN, CLIENT_PN}, e.params);
        }

        // Don't need to check TestRecorder.compareRecordsToExpected for the build:
        // We do that in another test, and shouldn't have 2 places to update when we update that sequence.

        /* Undo the build or move; longest-road player should change back */

        records.clear();
        tcli.undoPutOrMovePieceRequest(ga, pieceType, pieceCoord, movedFromCoord);
        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        if ((pieceType == SOCPlayingPiece.ROAD) || (pieceType == SOCPlayingPiece.SHIP))
            assertNull(testDesc + ": cleared it", board.roadOrShipAtEdge(pieceCoord));
        else
            assertNull(testDesc + ": cleared it", board.settlementAtNode(pieceCoord));
        assertEquals(startPieceCount, cliPl.getNumPieces(pieceType));
        assertEquals(2, cliPl.getPublicVP());
        assertEquals(OTHER_PN, ga.getPlayerWithLongestRoad().getPlayerNumber());
        assertEquals(OTHER_PN, gaAtCli.getPlayerWithLongestRoad().getPlayerNumber());
        assertArrayEquals(PL_START_RES_ARR, cliPl.getResources().getAmounts(false));
        assertArrayEquals(PL_START_RES_ARR, cliPlAtCli.getResources().getAmounts(false));
        if (shipRouteBecomesClosed != null)
            for (int edge : shipRouteBecomesClosed)
                assertFalse(testDesc + ": ship at 0x" + Integer.toHexString(edge) + " should be open again",
                    ((SOCShip) board.roadOrShipAtEdge(edge)).isClosed());
        if (shipRouteRemainsOpen != null)
            for (int edge : shipRouteRemainsOpen)
                assertFalse(testDesc + ": ship at 0x" + Integer.toHexString(edge) + " should still be open",
                    ((SOCShip) board.roadOrShipAtEdge(edge)).isClosed());

        act = ga.getLastAction();
        assertNotNull(act);
        if (movedFromCoord == 0)
        {
            assertEquals(GameAction.ActionType.UNDO_BUILD_PIECE, act.actType);
            assertEquals(pieceType, act.param1);
            assertEquals(pieceCoord, act.param2);
        } else {
            assertEquals(GameAction.ActionType.UNDO_MOVE_PIECE, act.actType);
            assertEquals(pieceType, act.param1);
            assertEquals(movedFromCoord, act.param2);
            assertEquals(pieceCoord, act.param3);
        }
        {
            List<GameAction.Effect> effects = act.effects;
            assertNotNull(effects);
            assertEquals(((movedFromCoord == 0) ? 2 : 1) + ((shipRouteBecomesClosed != null) ? 1 : 0), effects.size());

            GameAction.Effect e = effects.get(0);
            if (shipRouteBecomesClosed != null)
            {
                assertEquals(testDesc, GameAction.EffectType.CLOSE_SHIP_ROUTE, e.eType);
                assertNotNull(testDesc, e.params);
                assertEquals(testDesc, shipRouteBecomesClosed.length, e.params.length);
                for (int edge : shipRouteBecomesClosed)
                     assertTrue(Arrays.stream(e.params).anyMatch(i -> i == edge));
                e = effects.get(1);
            }
            if (movedFromCoord == 0)
            {
                assertEquals(GameAction.EffectType.DEDUCT_COST_FROM_PLAYER, e.eType);
                assertNull(e.params);
                e = effects.get(effects.size() - 1);
            }
            assertEquals(GameAction.EffectType.CHANGE_LONGEST_ROAD_PLAYER, e.eType);
            assertArrayEquals(new int[]{OTHER_PN, CLIENT_PN}, e.params);
        }
    }

    /**
     * Tests buying a dev card.
     * Expands on quick test done in {@link TestRecorder#testLoadAndBasicSequences()}.
     * @see #testPlayDevCards()
     */
    @Test
    public void testBuyDevCard()
        throws IOException
    {
        assertNotNull(srv);

        for (int observabilityMode = 0; observabilityMode <= 2; ++observabilityMode)
        {
            testOne_BuyDevCard(observabilityMode, false, false);
            testOne_BuyDevCard(observabilityMode, false, true);
            testOne_BuyDevCard(observabilityMode, true, false);
            testOne_BuyDevCard(observabilityMode, true, true);
        }
    }

    private void testOne_BuyDevCard
        (final int observabilityMode, final boolean clientAsRobot, final boolean othersAsRobot)
        throws IOException
    {
        final String CLIENT_NAME
            = "testBuyDevCard_" + observabilityMode + (clientAsRobot ? "_r" : "_h") + (othersAsRobot ? "_r" : "_h");

        final StartedTestGameObjects objs =
            TestRecorder.connectLoadJoinResumeGame
                (srv, CLIENT_NAME, null, 0, null, true, observabilityMode, clientAsRobot, othersAsRobot);
        final DisplaylessTesterClient tcli = objs.tcli;
        final SOCGame ga = objs.gameAtServer;
        final SOCPlayer cliPl = objs.clientPlayer;
        final Vector<EventEntry> records = objs.records;

        records.clear();
        assertEquals(23, ga.getNumDevCards());
        assertEquals(5, cliPl.getInventory().getTotal());
        tcli.buyDevCard(ga);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertEquals(22, ga.getNumDevCards());
        assertEquals(6, cliPl.getInventory().getTotal());

        StringBuilder comparesBuyCard = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCPlayerElements:", "|playerNum=3|actionType=LOSE|e2=1,e3=1,e4=1"},
                {"p3:SOCDevCardAction:", "|playerNum=3|actionType=DRAW|cardType=5"},  // type known from savegame devCardDeck
                {
                    "!p3:SOCDevCardAction:",
                    (observabilityMode == 0)
                        ? "|playerNum=3|actionType=DRAW|cardType=0"
                        : "|playerNum=3|actionType=DRAW|cardType=5"
                },
                {"all:SOCSimpleAction:", "|pn=3|actType=1|v1=22|v2=0"},  // DEVCARD_BOUGHT
                {"all:SOCGameState:", "|state=20"}
            }, false);

        /* leave game, check results */

        srv.destroyGameAndBroadcast(ga.getName(), null);
        tcli.destroy();

        if (comparesBuyCard != null)
        {
            comparesBuyCard.insert(0, "For test " + CLIENT_NAME + ": Message mismatch: ");
            System.err.println(comparesBuyCard);
            fail(comparesBuyCard.toString());
        }
    }

    /**
     * Tests playing dev cards.
     * @see #testBuyDevCard()
     */
    @Test
    public void testPlayDevCards()
        throws IOException
    {
        assertNotNull(srv);

        for (int observabilityMode = 0; observabilityMode <= 2; ++observabilityMode)
        {
            testOne_PlayDevCards(observabilityMode, false, false);
            testOne_PlayDevCards(observabilityMode, false, true);
            testOne_PlayDevCards(observabilityMode, true, false);
            testOne_PlayDevCards(observabilityMode, true, true);
        }
    }

    private void testOne_PlayDevCards
        (final int observabilityMode, final boolean clientAsRobot, final boolean othersAsRobot)
        throws IOException
    {
        // unique client nickname, in case tests run in parallel
        final String CLIENT_NAME
            = "testPlayDevCrd_" + observabilityMode + (clientAsRobot ? "_r" : "_h") + (othersAsRobot ? "_r" : "_h");

        final int CLIENT_PN = 3;
        final StartedTestGameObjects objs =
            TestRecorder.connectLoadJoinResumeGame
                (srv, CLIENT_NAME, null, 0, null, true, observabilityMode, clientAsRobot, othersAsRobot);
        final DisplaylessTesterClient tcli = objs.tcli;
        // final SavedGameModel sgm = objs.sgm;
        final SOCGame ga = objs.gameAtServer;
        final SOCBoardLarge board = (SOCBoardLarge) objs.board;
        final SOCPlayer cliPl = objs.clientPlayer;
        assertEquals(CLIENT_PN, cliPl.getPlayerNumber());
        final Vector<EventEntry> records = objs.records;

        List<Integer> expectedCardsPlayed = new ArrayList<>(Arrays.asList(SOCDevCardConstants.KNIGHT));
        assertEquals(expectedCardsPlayed, cliPl.getDevCardsPlayed());
        assertEquals(1, cliPl.getNumKnights());
        assertEquals(0, cliPl.numDISCCards);
        assertEquals(0, cliPl.numMONOCards);
        assertEquals(0, cliPl.numRBCards);

        /* monopoly: Sheep (victims pn=1 and pn=2 both have some sheep) */

        records.clear();
        assertArrayEquals(new int[]{3, 3, 3, 4, 4}, cliPl.getResources().getAmounts(false));
        tcli.playDevCard(ga, SOCDevCardConstants.MONO);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertEquals(SOCGame.WAITING_FOR_MONOPOLY, ga.getGameState());
        assertEquals(1, cliPl.numMONOCards);
        expectedCardsPlayed.add(SOCDevCardConstants.MONO);
        assertEquals(expectedCardsPlayed, cliPl.getDevCardsPlayed());
        tcli.pickResourceType(ga, SOCResourceConstants.SHEEP);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertEquals(SOCGame.PLAY1, ga.getGameState());
        assertArrayEquals(new int[]{3, 3, 6, 4, 4}, cliPl.getResources().getAmounts(false));

        StringBuilder comparesMono = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCDevCardAction:", "|playerNum=3|actionType=PLAY|cardType=3"},
                {"all:SOCPlayerElement:", "|playerNum=3|actionType=SET|elementType=19|amount=1"},  // PLAYED_DEV_CARD_FLAG
                {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " played a Monopoly card."},
                {"all:SOCGameState:", "|state=53"},
                {"all:SOCPlayerElement:", "|playerNum=1|actionType=SET|elementType=3|amount=0|news=Y"},
                {"all:SOCResourceCount:", "|playerNum=1|count=6"},
                {"all:SOCPlayerElement:", "|playerNum=2|actionType=SET|elementType=3|amount=0|news=Y"},
                {"all:SOCResourceCount:", "|playerNum=2|count=2"},
                {"all:SOCPlayerElement:", "|playerNum=3|actionType=GAIN|elementType=3|amount=3"},
                {"all:SOCSimpleAction:", "|pn=3|actType=3|v1=3|v2=3"},  // 3 == RSRC_TYPE_MONOPOLIZED
                (othersAsRobot ? null : new String[]{"p1:SOCGameServerText:", "|text=" + CLIENT_NAME + "'s Monopoly took your 1 sheep."}),
                (othersAsRobot ? null : new String[]{"p2:SOCGameServerText:", "|text=" + CLIENT_NAME + "'s Monopoly took your 2 sheep."}),
                {"all:SOCGameState:", "|state=20"}
            }, false);

        /* discovery/year of plenty */

        StringBuilder comparesDisc = null;
        if (observabilityMode == 0)
        {
            records.clear();
            cliPl.setPlayedDevCard(false);  // bend rules to skip waiting for our next turn
            tcli.playDevCard(ga, SOCDevCardConstants.DISC);

            try { Thread.sleep(60); }
            catch(InterruptedException e) {}
            assertEquals(SOCGame.WAITING_FOR_DISCOVERY, ga.getGameState());
            assertEquals(1, cliPl.numDISCCards);
            expectedCardsPlayed.add(SOCDevCardConstants.DISC);
            assertEquals(expectedCardsPlayed, cliPl.getDevCardsPlayed());
            tcli.pickResources(ga, new SOCResourceSet(0, 1, 0, 1, 0, 0));

            try { Thread.sleep(60); }
            catch(InterruptedException e) {}
            assertEquals(SOCGame.PLAY1, ga.getGameState());
            assertArrayEquals(new int[]{3, 4, 6, 5, 4}, cliPl.getResources().getAmounts(false));

            comparesDisc = TestRecorder.compareRecordsToExpected
                (records, new String[][]
                {
                    {"all:SOCDevCardAction:", "|playerNum=3|actionType=PLAY|cardType=2"},
                    {"all:SOCPlayerElement:", "|playerNum=3|actionType=SET|elementType=19|amount=1"},  // PLAYED_DEV_CARD_FLAG
                    {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " played a Year of Plenty card."},
                    {"all:SOCGameState:", "|state=52"},
                    {"all:SOCPickResources:", "|resources=clay=0|ore=1|sheep=0|wheat=1|wood=0|unknown=0|pn=3|reason=2"},
                    {"all:SOCGameState:", "|state=20"}
                }, false);
        }

        /* road building, gain longest road */

        // because this increases VP, is tested in every observabilityMode even though it's public
        records.clear();
        assertEquals(null, ga.getPlayerWithLongestRoad());
        assertEquals(2, cliPl.getPublicVP());
        assertTrue(board.roadOrShipAtEdge(0x609) instanceof SOCRoad);
        final int ROAD_EDGE_1 = 0x70a, ROAD_EDGE_2 = 0x809;
        assertNull(board.roadOrShipAtEdge(ROAD_EDGE_1));
        assertNull(board.roadOrShipAtEdge(ROAD_EDGE_2));
        cliPl.setPlayedDevCard(false);
        tcli.playDevCard(ga, SOCDevCardConstants.ROADS);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertEquals(SOCGame.PLACING_FREE_ROAD1, ga.getGameState());
        assertEquals(1, cliPl.numRBCards);
        expectedCardsPlayed.add(SOCDevCardConstants.ROADS);
        assertEquals(expectedCardsPlayed, cliPl.getDevCardsPlayed());
        tcli.putPiece(ga, new SOCRoad(cliPl, ROAD_EDGE_1, board));

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertEquals(SOCGame.PLACING_FREE_ROAD2, ga.getGameState());
        assertTrue(board.roadOrShipAtEdge(ROAD_EDGE_1) instanceof SOCRoad);
        GameAction act = ga.getLastAction();
        assertNotNull(act);
        assertEquals(GameAction.ActionType.BUILD_PIECE, act.actType);
        assertEquals(SOCPlayingPiece.ROAD, act.param1);
        assertEquals(ROAD_EDGE_1, act.param2);
        assertEquals(CLIENT_PN, act.param3);
        assertNull(act.effects);
        tcli.putPiece(ga, new SOCRoad(cliPl, ROAD_EDGE_2, board));

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertEquals(SOCGame.PLAY1, ga.getGameState());
        assertTrue(board.roadOrShipAtEdge(ROAD_EDGE_2) instanceof SOCRoad);
        assertEquals(cliPl, ga.getPlayerWithLongestRoad());
        assertEquals(4, cliPl.getPublicVP());
        act = ga.getLastAction();
        assertNotNull(act);
        assertEquals(GameAction.ActionType.BUILD_PIECE, act.actType);
        assertEquals(SOCPlayingPiece.ROAD, act.param1);
        assertEquals(ROAD_EDGE_2, act.param2);
        assertEquals(CLIENT_PN, act.param3);
        {
            List<GameAction.Effect> effects = act.effects;
            assertNotNull(effects);
            assertEquals(1, effects.size());

            GameAction.Effect e = effects.get(0);
            assertEquals(GameAction.EffectType.CHANGE_LONGEST_ROAD_PLAYER, e.eType);
            assertArrayEquals(new int[]{-1, CLIENT_PN}, e.params);
        }

        StringBuilder comparesRoadBuild = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCDevCardAction:", "|playerNum=3|actionType=PLAY|cardType=1"},
                {"all:SOCPlayerElement:", "|playerNum=3|actionType=SET|elementType=19|amount=1"},  // PLAYED_DEV_CARD_FLAG
                {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " played a Road Building card."},
                {"all:SOCGameState:", "|state=40"},
                {"p3:SOCGameServerText:", "|text=You may place 2 roads/ships."},
                {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " built a road."},
                {"all:SOCPutPiece:", "|playerNumber=3|pieceType=0|coord=70a"},
                {"all:SOCGameState:", "|state=41"},
                {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " built a road."},
                {"all:SOCPutPiece:", "|playerNumber=3|pieceType=0|coord=809"},
                {"all:SOCGameElements:", "|e6=3"},  // LONGEST_ROAD_PLAYER
                {"all:SOCGameState:", "|state=20"}
            }, false);

        /* soldier (move pirate ship) */

        records.clear();

        final int PIRATE_HEX = 0xd0a;
        // victim's ship should be sole adjacent piece
        {
            List<SOCPlayer> players = ga.getPlayersShipsOnHex(PIRATE_HEX);
            assertNotNull(players);
            assertEquals(1, players.size());
            assertEquals(1, players.get(0).getPlayerNumber());

            final int EXPECTED_VICTIM_SHIP_EDGE = 0xd09;
            SOCRoutePiece oppoShip = board.roadOrShipAtEdge(EXPECTED_VICTIM_SHIP_EDGE);
            assertTrue(oppoShip instanceof SOCShip);
            assertEquals(1, oppoShip.getPlayerNumber());

            final int[] PIRATE_HEX_OTHER_EDGES = {0xc09, 0xc0a, 0xd0b, 0xe0a, 0xe09};
            for (final int edge : PIRATE_HEX_OTHER_EDGES)
                assertNull(board.roadOrShipAtEdge(edge));
        }

        assertNotEquals("pirate not moved there yet", PIRATE_HEX, board.getPirateHex());
        cliPl.setPlayedDevCard(false);
        tcli.playDevCard(ga, SOCDevCardConstants.KNIGHT);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertTrue(cliPl.hasPlayedDevCard());
        assertEquals(2, cliPl.getNumKnights());
        expectedCardsPlayed.add(SOCDevCardConstants.KNIGHT);
        assertEquals(expectedCardsPlayed, cliPl.getDevCardsPlayed());
        assertEquals(SOCGame.WAITING_FOR_ROBBER_OR_PIRATE, ga.getGameState());
        tcli.choosePlayer(ga, SOCChoosePlayer.CHOICE_MOVE_PIRATE);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertEquals(SOCGame.PLACING_PIRATE, ga.getGameState());
        tcli.moveRobber(ga, cliPl, -PIRATE_HEX);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertEquals("new pirateHex", PIRATE_HEX, board.getPirateHex());
        SOCMoveRobberResult robRes = ga.getRobberyResult();
        assertNotNull(robRes);
        int resType = robRes.getLoot();
        assertTrue(resType > 0);

        StringBuilder comparesMovePirate = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " played a Soldier card."},
                {"all:SOCDevCardAction:", "|playerNum=3|actionType=PLAY|cardType=9"},
                {"all:SOCPlayerElement:", "|playerNum=3|actionType=SET|elementType=19|amount=1"},  // PLAYED_DEV_CARD_FLAG
                {"all:SOCPlayerElement:", "|playerNum=3|actionType=GAIN|elementType=15|amount=1"},  // NUMKNIGHTS
                {"all:SOCGameState:", "|state=54"},
                {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " must choose to move the robber or the pirate."},
                {"all:SOCGameState:", "|state=34"},
                {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " will move the pirate ship."},
                {"all:SOCMoveRobber:", "|playerNumber=3|coord=-d0a"},
                {
                    (observabilityMode != 2) ? "p3:SOCRobberyResult:" : "all:SOCRobberyResult:",
                    "|perp=3|victim=1|resType=" + resType + "|amount=1|isGainLose=true"
                },
                (observabilityMode != 2)
                    ? new String[]{"p1:SOCRobberyResult:", "|perp=3|victim=1|resType=" + resType + "|amount=1|isGainLose=true"}
                    : null,
                (observabilityMode != 2)
                    ? new String[]{"!p[3, 1]:SOCRobberyResult:", "|perp=3|victim=1|resType=6|amount=1|isGainLose=true"}
                    : null,
                {"all:SOCGameState:", "|state=20"},
            }, false);

        /* soldier (move robber), gain largest army */

        records.clear();
        cliPl.setPlayedDevCard(false);
        assertEquals(null, ga.getPlayerWithLargestArmy());
        assertEquals(4, cliPl.getPublicVP());
        assertEquals(2, cliPl.getNumKnights());

        final int ROBBER_HEX = 0x703;
        assertNotEquals("robber not moved there yet", ROBBER_HEX, board.getRobberHex());
        // victim's settlement should be sole adjacent piece
        {
            final int EXPECTED_VICTIM_SETTLEMENT_NODE = 0x604;

            Set<SOCPlayingPiece> pp = new HashSet<>();
            List<SOCPlayer> players = ga.getPlayersOnHex(ROBBER_HEX, pp);

            assertNotNull(players);
            assertEquals(1, players.size());
            assertEquals(2, players.get(0).getPlayerNumber());

            assertEquals(1, pp.size());
            SOCPlayingPiece vset = (SOCPlayingPiece) (pp.toArray()[0]);
            assertTrue(vset instanceof SOCSettlement);
            assertEquals(2, vset.getPlayerNumber());
            assertEquals(EXPECTED_VICTIM_SETTLEMENT_NODE, vset.getCoordinates());

            assertTrue(board.settlementAtNode(EXPECTED_VICTIM_SETTLEMENT_NODE) instanceof SOCSettlement);

            final int[] ROBBER_HEX_OTHER_NODES = {0x602, 0x603, 0x802, 0x803, 0x804};
            for (final int node : ROBBER_HEX_OTHER_NODES)
                assertNull(board.settlementAtNode(node));
        }

        cliPl.setPlayedDevCard(false);
        tcli.playDevCard(ga, SOCDevCardConstants.KNIGHT);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertTrue(cliPl.hasPlayedDevCard());
        assertEquals(3, cliPl.getNumKnights());
        expectedCardsPlayed.add(SOCDevCardConstants.KNIGHT);
        assertEquals(expectedCardsPlayed, cliPl.getDevCardsPlayed());
        assertEquals(6, cliPl.getPublicVP());
        assertEquals(cliPl, ga.getPlayerWithLargestArmy());
        assertEquals(SOCGame.WAITING_FOR_ROBBER_OR_PIRATE, ga.getGameState());
        tcli.choosePlayer(ga, SOCChoosePlayer.CHOICE_MOVE_ROBBER);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertEquals(SOCGame.PLACING_ROBBER, ga.getGameState());
        tcli.moveRobber(ga, cliPl, ROBBER_HEX);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertEquals("new robberHex", ROBBER_HEX, board.getRobberHex());
        robRes = ga.getRobberyResult();
        assertNotNull(robRes);
        resType = robRes.getLoot();
        assertTrue(resType > 0);

        StringBuilder comparesMoveRobber = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " played a Soldier card."},
                {"all:SOCDevCardAction:", "|playerNum=3|actionType=PLAY|cardType=9"},
                {"all:SOCPlayerElement:", "|playerNum=3|actionType=SET|elementType=19|amount=1"},  // PLAYED_DEV_CARD_FLAG
                {"all:SOCPlayerElement:", "|playerNum=3|actionType=GAIN|elementType=15|amount=1"},  // NUMKNIGHTS
                {"all:SOCGameElements:", "|e5=3"},  // LARGEST_ARMY_PLAYER
                {"all:SOCGameState:", "|state=54"},
                {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " must choose to move the robber or the pirate."},
                {"all:SOCGameState:", "|state=33"},
                {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " will move the robber."},
                {"all:SOCMoveRobber:", "|playerNumber=3|coord=703"},
                {
                    (observabilityMode != 2) ? "p3:SOCRobberyResult:" : "all:SOCRobberyResult:",
                    "|perp=3|victim=2|resType=" + resType + "|amount=1|isGainLose=true"
                },
                (observabilityMode != 2)
                    ? new String[]{"p2:SOCRobberyResult:", "|perp=3|victim=2|resType=" + resType + "|amount=1|isGainLose=true"}
                    : null,
                (observabilityMode != 2)
                    ? new String[]{"!p[3, 2]:SOCRobberyResult:", "|perp=3|victim=2|resType=6|amount=1|isGainLose=true"}
                    : null,
                {"all:SOCGameState:", "|state=20"}
            }, false);

        /* leave game, consolidate results */

        srv.destroyGameAndBroadcast(ga.getName(), null);
        tcli.destroy();

        StringBuilder compares = new StringBuilder();
        if (comparesMono != null)
        {
            compares.append("Monopoly: Message mismatch: ");
            compares.append(comparesMono);
        }
        if (comparesDisc != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Discovery/Year of Plenty: Message mismatch: ");
            compares.append(comparesDisc);
        }
        if (comparesRoadBuild != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Road Building: Message mismatch: ");
            compares.append(comparesRoadBuild);
        }
        if (comparesMovePirate != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Move Pirate: Message mismatch: ");
            compares.append(comparesMovePirate);
        }
        if (comparesMoveRobber != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Move Robber: Message mismatch: ");
            compares.append(comparesMoveRobber);
        }

        if (compares.length() > 0)
        {
            compares.insert(0, "For test " + CLIENT_NAME + ": ");
            System.err.println(compares);
            fail(compares.toString());
        }
    }

    /**
     * Test rolling dice at start of turn, and all 4 possible sequences in this test game:
     *<UL>
     * <LI> players receive resources
     * <LI> no one receives resources
     * <LI> roll 7, steal from a player (same sequence covers: steal from no one)
     * <LI> roll 7, discard, steal from a player
     *</UL>
     * @see #testRollDiceGoldHexGain()
     */
    @Test
    public void testRollDiceRsrcsOrMoveRobber()
        throws IOException
    {
        assertNotNull(srv);

        for (int observabilityMode = 0; observabilityMode <= 2; ++observabilityMode)
        {
            // These messages should have no differences between human and robot clients.
            // We'll test all 4 combinations just in case.
            testOne_RollDiceRsrcsOrMoveRobber(observabilityMode, false, false);
            testOne_RollDiceRsrcsOrMoveRobber(observabilityMode, false, true);
            testOne_RollDiceRsrcsOrMoveRobber(observabilityMode, true, false);
            testOne_RollDiceRsrcsOrMoveRobber(observabilityMode, true, true);
        }
    }

    private void testOne_RollDiceRsrcsOrMoveRobber
        (final int observabilityMode, final boolean clientAsRobot, final boolean othersAsRobot)
        throws IOException
    {
        // CLIENT will always be the current player. CLIENT2 will be used only to test 2 players discarding.
        final String CLIENT_NAME
            = "testRollDice_"  + observabilityMode + (clientAsRobot ? "_r" : "_h") + (othersAsRobot ? "_r" : "_h"),
            CLIENT2_NAME
            = "testRollDice2_" + observabilityMode + (clientAsRobot ? "_r" : "_h") + (othersAsRobot ? "_r" : "_h");
        final int CLIENT_PN = 3, CLIENT2_PN = 1;

        final StartedTestGameObjects objs =
            TestRecorder.connectLoadJoinResumeGame
                (srv, CLIENT_NAME, CLIENT2_NAME, CLIENT2_PN,
                 null, false, observabilityMode, clientAsRobot, othersAsRobot);
        final DisplaylessTesterClient tcli = objs.tcli, tcli2 = objs.tcli2;
        final SavedGameModel sgm = objs.sgm;
        final SOCGame ga = objs.gameAtServer;
        final SOCBoardLarge board = (SOCBoardLarge) objs.board;
        final SOCPlayer cliPl = objs.clientPlayer, cli2Pl = objs.client2Player;
        final Vector<EventEntry> records = objs.records;

        assertTrue(ga.isSeatVacant(0));
        assertFalse(ga.isSeatVacant(CLIENT2_PN));
        assertEquals(CLIENT_PN, cliPl.getPlayerNumber());
        assertEquals(CLIENT2_PN, cli2Pl.getPlayerNumber());

        // clear debug player's resources so no one needs to discard on 7;
        // once that sequence is validated, will change so client and CLIENT2 must discard on 7
        assertEquals(new SOCResourceSet(1, 2, 1, 3, 0, 0), ga.getPlayer(CLIENT2_PN).getResources());
        cliPl.getResources().setAmounts(new SOCResourceSet(0, 3, 1, 2, 0, 0));

        // allow 7s to be rolled
        ga.getGameOptions().remove("N7");

        final SOCResourceSet[] savedRsrcs = new SOCResourceSet[ga.maxPlayers];
        for (int pn = 0; pn < ga.maxPlayers; ++pn)
            savedRsrcs[pn] = new SOCResourceSet(ga.getPlayer(pn).getResources());  // make independent copy

        sgm.gameState = SOCGame.ROLL_OR_CARD;
        TestRecorder.resumeLoadedGame(ga, srv, objs.tcliConn);

        // Validate expected resources gained by each player number for each dice number vs artifact's board layout:
        final int[][] RSRC_GAINED_COUNTS =
            {
                null, null,
                /* 2 */ {0, 1, 0, 0}, {0, 0, 1, 1}, {0, 0, 1, 0}, {0, 0, 1, 1}, {0, 1, 2, 0},
                /* 7 */ null,
                /* 8 */ null,
                {0, 1, 0, 2}, {0, 2, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}
            };
        for (int diceNumber = 2; diceNumber <= 12; ++diceNumber)
        {
            final int[] counts = RSRC_GAINED_COUNTS[diceNumber];
            for (int pn = 0; pn < ga.maxPlayers; ++pn)
            {
                SOCResourceSet rs = ga.getResourcesGainedFromRoll(ga.getPlayer(pn), diceNumber);
                if (rs.isEmpty())
                    assertTrue
                        ("no res on board for: dice=" + diceNumber + " pn=" + pn,
                         (counts == null) || (counts[pn] == 0));
                else
                {
                    int n = rs.getTotal();
                    assertTrue
                        ("num res on board = " + n + " for: dice=" + diceNumber + " pn=" + pn,
                         (counts != null) && (counts[pn] == n));
                }
            }
        }

        StringBuilder comparesRsrcs = null, comparesNoRsrcs = null,
            compares7MoveRobber = null, compares7DiscardMove = null;
        boolean testedRsrcs = false, testedNoRsrcs = false, tested7 = false, tested7Discard = false;

        while (! (testedRsrcs && testedNoRsrcs && tested7 && tested7Discard))
        {
            ga.setGameState(SOCGame.ROLL_OR_CARD);
            for (int pn = 0; pn < ga.maxPlayers; ++pn)
                ga.getPlayer(pn).getResources().setAmounts(savedRsrcs[pn]);

            records.clear();
            tcli.rollDice(ga);

            try { Thread.sleep(60); }
            catch(InterruptedException e) {}
            final int diceNumber = ga.getCurrentDice();

            if (diceNumber != 7)
            {
                assertEquals(SOCGame.PLAY1, ga.getGameState());

                int nGainingPlayers = 0;
                final int[] counts = RSRC_GAINED_COUNTS[diceNumber];
                for (int pn = 1; pn < ga.maxPlayers; ++pn)  // pn 0 is vacant
                {
                    SOCPlayer pl = ga.getPlayer(pn);
                    int nGains = pl.getRolledResources().getTotal();
                    assertEquals
                        ((counts != null) ? counts[pn] : 0, nGains);
                    if (nGains > 0)
                        ++nGainingPlayers;
                }

                if (counts != null)
                {
                    if (! testedRsrcs)
                    {
                        // SOCDiceResultResources has a very specific format; see its class javadoc.
                        // We'll check that here.

                        StringBuilder diceResRsrc = new StringBuilder();
                        List<Integer> playerNums = new ArrayList<>();  // player numbers gaining resources
                        List<StringBuilder> playerRsrcElems = new ArrayList<>();  // same indexes as pn

                        // build expected SOCDiceResultResources and SOCPlayerElements strings
                        for (int pn = 1; pn < ga.maxPlayers; ++pn)
                        {
                            SOCPlayer pl = ga.getPlayer(pn);
                            SOCResourceSet rsGained = pl.getRolledResources();
                            int nGain = rsGained.getTotal();
                            if (nGain == 0)
                                continue;

                            SOCResourceSet rsPlayer = pl.getResources();
                            playerNums.add(Integer.valueOf(pn));
                            StringBuilder rsStrAdd = new StringBuilder("|playerNum=" + pn + "|actionType=SET|");
                            for (int rtype = SOCResourceConstants.CLAY; rtype <= SOCResourceConstants.WOOD; ++rtype)
                            {
                                if (rtype > SOCResourceConstants.CLAY)
                                    rsStrAdd.append(',');
                                rsStrAdd.append("e" + rtype + "=" + rsPlayer.getAmount(rtype));
                            }
                            playerRsrcElems.add(rsStrAdd);

                            if (diceResRsrc.length() == 0)
                                diceResRsrc.append
                                    ("game=" + ga.getName() + "|p=" + nGainingPlayers);  // first data fields of message
                            else
                                diceResRsrc.append("|p=0");  // separator from previous player

                            diceResRsrc.append("|p=" + pn);
                            diceResRsrc.append("|p=" + rsPlayer.getTotal());
                            for (int rtype = SOCResourceConstants.CLAY; rtype <= SOCResourceConstants.WOOD; ++rtype)
                            {
                                int n = rsGained.getAmount(rtype);
                                if (n == 0)
                                    continue;
                                diceResRsrc.append("|p=" + n);
                                diceResRsrc.append("|p=" + rtype);
                            }
                        }

                        /*
                        example:
                        all:SOCDiceResult:game=message-seqs|param=3
                        all:SOCDiceResultResources:game=message-seqs|p=2|p=2|p=5|p=1|p=3|p=0|p=3|p=7|p=1|p=4
                        p2:SOCPlayerElements:game=message-seqs|playerNum=2|actionType=SET|e1=1,e2=1,e3=3,e4=0,e5=0
                        p3:SOCPlayerElements:game=message-seqs|playerNum=3|actionType=SET|e1=0,e2=3,e3=1,e4=3,e5=0
                        all:SOCGameState:game=message-seqs|state=20
                         */
                        ArrayList<String[]> recordsList = new ArrayList<>();
                        recordsList.add(new String[]{"all:SOCDiceResult:", "|param=" + diceNumber});
                        recordsList.add(new String[]{"all:SOCDiceResultResources:", diceResRsrc.toString()});
                        for (int i = 0; i < playerNums.size(); ++i)
                        {
                            int pn = playerNums.get(i);
                            recordsList.add(new String[]
                                {
                                    "p" + pn + ":SOCPlayerElements:",
                                    playerRsrcElems.get(i).toString()
                                });
                        }
                        recordsList.add(new String[]{"all:SOCGameState:", "|state=20"});

                        comparesRsrcs = TestRecorder.compareRecordsToExpected
                            (records, recordsList.toArray(new String[recordsList.size()][]), false);

                        testedRsrcs = true;
                    }
                } else {
                    if (! testedNoRsrcs)
                    {
                        comparesNoRsrcs = TestRecorder.compareRecordsToExpected
                            (records, new String[][]
                            {
                                {"all:SOCDiceResult:", "|param=" + diceNumber},
                                {"all:SOCGameServerText:", "|text=No player gets anything."},
                                {"all:SOCGameState:", "|state=20"},
                            }, false);

                        testedNoRsrcs = true;
                    }
                }

            } else {
                /* 7: move robber, steal from 1 player */

                if (! tested7)
                {
                    assertEquals(SOCGame.WAITING_FOR_ROBBER_OR_PIRATE, ga.getGameState());

                    try { Thread.sleep(60); }
                    catch(InterruptedException e) {}
                    compares7MoveRobber = TestRecorder.moveRobberStealSequence
                        (tcli, ga, cliPl, observabilityMode, records,
                         new String[][]
                            {
                                {"all:SOCDiceResult:", "|param=7"},
                            });

                    tested7 = true;

                    // adjust player resources so CLIENT_PN, CLIENT2_PN will have to discard at next 7
                    savedRsrcs[CLIENT_PN]  = new SOCResourceSet(3, 3, 3, 4, 4, 0);
                    savedRsrcs[CLIENT2_PN] = new SOCResourceSet(0, 8, 0, 0, 0, 0);
                }
                else if (! tested7Discard)
                {
                    assertEquals(SOCGame.WAITING_FOR_DISCARDS, ga.getGameState());
                    assertArrayEquals(new int[]{3, 3, 3, 4, 4}, cliPl.getResources().getAmounts(false));

                    // send a discard message from CLIENT2 first
                    tcli2.discard(ga, new SOCResourceSet(0, 4, 0, 0, 0, 0));
                    try { Thread.sleep(60); }
                    catch(InterruptedException e) {}

                    tcli.discard(ga, new SOCResourceSet(0, 0, 0, 4, 4, 0));

                    try { Thread.sleep(60); }
                    catch(InterruptedException e) {}
                    assertEquals(SOCGame.WAITING_FOR_ROBBER_OR_PIRATE, ga.getGameState());
                    compares7DiscardMove = TestRecorder.moveRobberStealSequence
                        (tcli, ga, cliPl, observabilityMode, records,
                         new String[][]
                            {
                                {"all:SOCDiceResult:", "|param=7"},
                                {"all:SOCGameState:", "|state=50"},
                                {"all:SOCGameServerText:", "|text=" + CLIENT2_NAME + " and " + CLIENT_NAME + " need to discard."},
                                {"p1:SOCDiscardRequest:", "|numDiscards=4"},
                                {"p3:SOCDiscardRequest:", "|numDiscards=8"},
                                {
                                    ((observabilityMode != 2) ? "p1:SOCDiscard:" : "all:SOCDiscard:"),
                                    "|playerNum=1|resources=clay=0|ore=4|sheep=0|wheat=0|wood=0|unknown=0"
                                },
                                ((observabilityMode != 2)
                                    ? new String[]{"!p1:SOCDiscard:", "|playerNum=1|resources=clay=0|ore=0|sheep=0|wheat=0|wood=0|unknown=4"}
                                    : null),
                                {"all:SOCGameState:", "|state=50"},
                                {"all:SOCGameServerText:", "|text=" + CLIENT_NAME + " needs to discard."},
                                {
                                    ((observabilityMode != 2) ? "p3:SOCDiscard:" : "all:SOCDiscard:"),
                                    "|playerNum=3|resources=clay=0|ore=0|sheep=0|wheat=4|wood=4|unknown=0"
                                },
                                ((observabilityMode != 2)
                                    ? new String[]{"!p3:SOCDiscard:", "|playerNum=3|resources=clay=0|ore=0|sheep=0|wheat=0|wood=0|unknown=8"}
                                    : null)
                            });

                    tested7Discard = true;
                }

                // reset, for next robber move
                board.setRobberHex(0x90a, false);
            }
        }

        /* leave game, consolidate results */

        srv.destroyGameAndBroadcast(ga.getName(), null);
        tcli.destroy();

        StringBuilder compares = new StringBuilder();
        if (comparesRsrcs != null)
        {
            compares.append("Players get resources: Message mismatch: ");
            compares.append(comparesRsrcs);
        }
        if (comparesNoRsrcs != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("No one gets resources: Message mismatch: ");
            compares.append(comparesNoRsrcs);
        }
        if (compares7MoveRobber != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Roll 7 move robber: Message mismatch: ");
            compares.append(compares7MoveRobber);
        }
        if (compares7DiscardMove != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Roll 7 discard move robber: Message mismatch: ");
            compares.append(compares7DiscardMove);
        }

        if (compares.length() > 0)
        {
            compares.insert(0, "For test " + CLIENT_NAME + ": ");
            System.err.println(compares);
            fail(compares.toString());
        }
    }

    /**
     * Test rolling dice at start of turn, eventually roll 8, gain resources from gold hex.
     * @see #testRollDiceRsrcsOrMoveRobber()
     */
    @Test
    public void testRollDiceGoldHexGain()
        throws IOException
    {
        assertNotNull(srv);

        // These messages should have no differences between human and robot clients.
        // We'll test all 4 combinations just in case.
        testOne_RollDiceGoldHexGain(false, false);
        testOne_RollDiceGoldHexGain(false, true);
        testOne_RollDiceGoldHexGain(true, false);
        testOne_RollDiceGoldHexGain(true, true);
    }

    private void testOne_RollDiceGoldHexGain
        (final boolean clientAsRobot, final boolean othersAsRobot)
        throws IOException
    {
        final String nameSuffix = (clientAsRobot ? 'r' : 'h') + (othersAsRobot ? "_r" : "_h");
        final String CLIENT_NAME = "testRollGold_p3_" + nameSuffix,
            CLIENT2_NAME = "testRollGold_p1_" + nameSuffix;
        final int CLIENT_PN = 3, CLIENT2_PN = 1;
        final int GOLD_DICE_NUM = 8;

        final StartedTestGameObjects objs =
            TestRecorder.connectLoadJoinResumeGame
                (srv, CLIENT_NAME, CLIENT2_NAME, CLIENT2_PN, null, false, 0, clientAsRobot, othersAsRobot);
        final DisplaylessTesterClient tcli = objs.tcli, tcli2 = objs.tcli2;
        final SavedGameModel sgm = objs.sgm;
        final SOCGame ga = objs.gameAtServer;
        final SOCBoardLarge board = (SOCBoardLarge) objs.board;
        final SOCPlayer cliPl = objs.clientPlayer, cli2Pl = objs.client2Player;
        final Vector<EventEntry> records = objs.records;

        assertEquals(SOCBoardLarge.GOLD_HEX, board.getHexTypeFromCoord(0xF05));
        assertEquals(GOLD_DICE_NUM, board.getNumberOnHexFromCoord(0xF05));
        assertTrue(ga.isSeatVacant(0));
        assertEquals(CLIENT_PN, cliPl.getPlayerNumber());

        // reminder: message-seqs.game.json has "N7" option to prevent 7s from being rolled
        // but just in case: clear debug player's resources to prevent discard on 7 from accumulated rolls
        final int[] RS_KNOWN_AMOUNTS_ARR = {0, 3, 1, 2, 0}, RS_KNOWN_PLUS_2_CLAY = {2, 3, 1, 2, 0},
            CLI2_RS_KNOWN_AMOUNTS_ARR = {1, 0, 3, 0, 2}, CLI2_RS_KNOWN_PLUS_SHEEP_WHEAT = {1, 0, 4, 1, 2};
        final SOCResourceSet RS_KNOWN = new SOCResourceSet(RS_KNOWN_AMOUNTS_ARR),
            CLI2_RS_KNOWN = new SOCResourceSet(CLI2_RS_KNOWN_AMOUNTS_ARR);
        cliPl.getResources().setAmounts(RS_KNOWN);
        cli2Pl.getResources().setAmounts(CLI2_RS_KNOWN);

        // change board at server to build some pieces, so client and client2 players will gain on 8 (GOLD_DICE_NUM):

        final int SHIP_EDGE = 0xe04, ISLAND_SETTLE_NODE = 0xe04;
        assertNull(board.roadOrShipAtEdge(SHIP_EDGE));
        assertNull(board.settlementAtNode(ISLAND_SETTLE_NODE));

        final int[] CLI2_SHIPS_EDGE = {0xe08, 0xe07, 0xe06};
        final int CLI2_ISLAND_SETTLE_NODE = 0xe06;
        for (int edge : CLI2_SHIPS_EDGE)
            assertNull(board.roadOrShipAtEdge(edge));
        assertNull(board.settlementAtNode(CLI2_ISLAND_SETTLE_NODE));

        ga.putPiece(new SOCShip(cliPl, SHIP_EDGE, board));
        ga.putPiece(new SOCSettlement(cliPl, ISLAND_SETTLE_NODE, board));
        ga.putPiece(new SOCCity(cliPl, ISLAND_SETTLE_NODE, board));

        for (int edge : CLI2_SHIPS_EDGE)
            ga.putPiece(new SOCShip(cli2Pl, edge, board));
        ga.putPiece(new SOCSettlement(cli2Pl, CLI2_ISLAND_SETTLE_NODE, board));
        ga.putPiece(new SOCCity(cli2Pl, CLI2_ISLAND_SETTLE_NODE, board));

        assertTrue(board.roadOrShipAtEdge(SHIP_EDGE) instanceof SOCShip);
        assertTrue(board.settlementAtNode(ISLAND_SETTLE_NODE) instanceof SOCCity);
        for (int edge : CLI2_SHIPS_EDGE)
            assertTrue(board.roadOrShipAtEdge(edge) instanceof SOCShip);
        assertTrue(board.settlementAtNode(CLI2_ISLAND_SETTLE_NODE) instanceof SOCCity);

        final int[] EXPECTED_GOLD_GAINS = {0, 2, 0, 2};
        for (int pn = 0; pn < ga.maxPlayers; ++pn)
            assertEquals
                ("pn[" + pn + "] gains", EXPECTED_GOLD_GAINS[pn],
                 ga.getResourcesGainedFromRoll(ga.getPlayer(pn), GOLD_DICE_NUM)
                     .getAmount(SOCResourceConstants.GOLD_LOCAL));

        // ready to resume
        sgm.gameState = SOCGame.ROLL_OR_CARD;
        TestRecorder.resumeLoadedGame(ga, srv, objs.tcliConn);

        StringBuilder compares = null;

        for (int diceNumber = 0; diceNumber != GOLD_DICE_NUM; )
        {
            ga.setGameState(SOCGame.ROLL_OR_CARD);
            cliPl.getResources().setAmounts(RS_KNOWN);
            cli2Pl.getResources().setAmounts(CLI2_RS_KNOWN);

            records.clear();
            tcli.rollDice(ga);

            try { Thread.sleep(60); }
            catch(InterruptedException e) {}
            diceNumber = ga.getCurrentDice();

            if (diceNumber != GOLD_DICE_NUM)
                continue;

            assertEquals(2, cliPl.getNeedToPickGoldHexResources());
            assertEquals(2, cli2Pl.getNeedToPickGoldHexResources());
            assertArrayEquals(RS_KNOWN_AMOUNTS_ARR, cliPl.getResources().getAmounts(false));
            assertArrayEquals(CLI2_RS_KNOWN_AMOUNTS_ARR, cli2Pl.getResources().getAmounts(false));
            assertEquals(SOCGame.WAITING_FOR_PICK_GOLD_RESOURCE, ga.getGameState());

            tcli.pickResources(ga, new SOCResourceSet(2, 0, 0, 0, 0, 0));  // 2 of same type

            try { Thread.sleep(60); }
            catch(InterruptedException e) {}
            assertEquals(0, cliPl.getNeedToPickGoldHexResources());
            assertEquals(2, cli2Pl.getNeedToPickGoldHexResources());
            assertArrayEquals(RS_KNOWN_PLUS_2_CLAY, cliPl.getResources().getAmounts(false));
            assertArrayEquals(CLI2_RS_KNOWN_AMOUNTS_ARR, cli2Pl.getResources().getAmounts(false));
            assertEquals(SOCGame.WAITING_FOR_PICK_GOLD_RESOURCE, ga.getGameState());

            tcli2.pickResources(ga, new SOCResourceSet(0, 0, 1, 1, 0, 0));  // 1 each of 2 different types

            try { Thread.sleep(60); }
            catch(InterruptedException e) {}
            assertEquals(0, cliPl.getNeedToPickGoldHexResources());
            assertEquals(0, cli2Pl.getNeedToPickGoldHexResources());
            assertArrayEquals(CLI2_RS_KNOWN_PLUS_SHEEP_WHEAT, cli2Pl.getResources().getAmounts(false));
            assertEquals(SOCGame.PLAY1, ga.getGameState());

            compares = TestRecorder.compareRecordsToExpected
                (records, new String[][]
                {
                    {"all:SOCDiceResult:game=", "|param=" + GOLD_DICE_NUM},
                    {"all:SOCGameServerText:game=", "|text=No player gets anything."},
                    {"all:SOCGameServerText:game=", "|text=" + CLIENT2_NAME + " and " + CLIENT_NAME + " need to pick resources from the gold hex."},
                    {"all:SOCPlayerElement:game=", "|playerNum=1|actionType=SET|elementType=101|amount=2"},  // NUM_PICK_GOLD_HEX_RESOURCES
                    {"p1:SOCSimpleRequest:game=", "|pn=1|reqType=1|v1=2|v2=0"},
                    {"all:SOCPlayerElement:game=", "|playerNum=3|actionType=SET|elementType=101|amount=2"},
                    {"p3:SOCSimpleRequest:game=", "|pn=3|reqType=1|v1=2|v2=0"},
                    {"all:SOCGameState:game=", "|state=56"},
                    {"all:SOCPickResources:game=", "|resources=clay=2|ore=0|sheep=0|wheat=0|wood=0|unknown=0|pn=3|reason=3"},
                    {"all:SOCPlayerElement:game=", "|playerNum=3|actionType=SET|elementType=101|amount=0"},
                    {"all:SOCGameState:game=", "|state=56"},
                    {"all:SOCPickResources:game=", "|resources=clay=0|ore=0|sheep=1|wheat=1|wood=0|unknown=0|pn=1|reason=3"},
                    {"all:SOCPlayerElement:game=", "|playerNum=1|actionType=SET|elementType=101|amount=0"},
                    {"all:SOCGameState:game=", "|state=20"}
                }, false);
        }

        /* leave game, consolidate results */

        srv.destroyGameAndBroadcast(ga.getName(), null);
        tcli.destroy();
        tcli2.destroy();

        if (compares != null)
        {
            compares.insert(0, "For test testRollDiceGoldHexGain(" + nameSuffix + "): Message mismatch: ");
            System.err.println(compares);
            fail(compares.toString());
        }
    }

    /**
     * Test 4:1 bank trades, 2:1 port trades, undoing those trades.
     */
    @Test
    public void testBankPortTrades()
        throws IOException
    {
        assertNotNull(srv);

        // These messages are all public with no text, and should have no differences between human and robot clients.
        // We'll test all 4 combinations just in case.
        testOne_BankPortTrades(false, false);
        testOne_BankPortTrades(false, true);
        testOne_BankPortTrades(true, false);
        testOne_BankPortTrades(true, true);
    }

    private void testOne_BankPortTrades
        (final boolean clientAsRobot, final boolean othersAsRobot)
        throws IOException
    {
        final String CLIENT_NAME = "testBankPortTrad_" + (clientAsRobot ? 'r' : 'h') + (othersAsRobot ? "_r" : "_h");

        final StartedTestGameObjects objs =
            TestRecorder.connectLoadJoinResumeGame
                (srv, CLIENT_NAME, null, 0, null, true, 0, clientAsRobot, othersAsRobot);
        final DisplaylessTesterClient tcli = objs.tcli;
        final SOCGame ga = objs.gameAtServer;
        final SOCBoardLarge board = (SOCBoardLarge) objs.board;
        final SOCPlayer cliPl = objs.clientPlayer;
        final Vector<EventEntry> records = objs.records;

        final SOCResourceSet SHEEP_1 = new SOCResourceSet(0, 0, 1, 0, 0, 0),
            WHEAT_4 = new SOCResourceSet(0, 0, 0, 4, 0, 0),
            WHEAT_2 = new SOCResourceSet(0, 0, 0, 2, 0, 0);

        /* 4:1 bank trade */

        records.clear();
        assertArrayEquals(new int[]{3, 3, 3, 4, 4}, cliPl.getResources().getAmounts(false));
        assertTrue(ga.canMakeBankTrade(WHEAT_4, SHEEP_1));
        assertFalse(ga.canMakeBankTrade(WHEAT_2, SHEEP_1));
        tcli.bankTrade(ga, WHEAT_4, SHEEP_1);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertArrayEquals(new int[]{3, 3, 4, 0, 4}, cliPl.getResources().getAmounts(false));

        StringBuilder compares_4_1 = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCBankTrade:", "|give=clay=0|ore=0|sheep=0|wheat=4|wood=0|unknown=0|get=clay=0|ore=0|sheep=1|wheat=0|wood=0|unknown=0|pn=3"}
            }, false);

        /* undo 4:1 bank trade */

        records.clear();
        assertTrue(ga.canUndoBankTrade(WHEAT_4, SHEEP_1));
        tcli.bankTrade(ga, SHEEP_1, WHEAT_4);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertArrayEquals(new int[]{3, 3, 3, 4, 4}, cliPl.getResources().getAmounts(false));

        StringBuilder compares_undo_4_1 = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCBankTrade:", "|give=clay=0|ore=0|sheep=1|wheat=0|wood=0|unknown=0|get=clay=0|ore=0|sheep=0|wheat=4|wood=0|unknown=0|pn=3"}
            }, false);

        /* build wheat port to enable 2:1 trades */

        final int SETTLEMENT_NODE = 0xc04;
        assertNull(board.settlementAtNode(SETTLEMENT_NODE));
        assertEquals(3, cliPl.getNumPieces(SOCPlayingPiece.SETTLEMENT));
        assertEquals(2, cliPl.getPublicVP());
        assertFalse(ga.canMakeBankTrade(WHEAT_2, SHEEP_1));
        tcli.putPiece(ga, new SOCSettlement(cliPl, SETTLEMENT_NODE, board));

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertNotNull("settlement built", board.settlementAtNode(SETTLEMENT_NODE));
        assertEquals(2, cliPl.getNumPieces(SOCPlayingPiece.SETTLEMENT));
        assertEquals(3, cliPl.getPublicVP());
        assertTrue(ga.canMakeBankTrade(WHEAT_2, SHEEP_1));
        assertArrayEquals(new int[]{2, 3, 2, 3, 3}, cliPl.getResources().getAmounts(false));

        // no need to check message records; another test already checks "build settlement" message sequence

        /* 2:1 port trade */

        records.clear();
        tcli.bankTrade(ga, WHEAT_2, SHEEP_1);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertArrayEquals(new int[]{2, 3, 3, 1, 3}, cliPl.getResources().getAmounts(false));

        StringBuilder compares_2_1 = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCBankTrade:", "|give=clay=0|ore=0|sheep=0|wheat=2|wood=0|unknown=0|get=clay=0|ore=0|sheep=1|wheat=0|wood=0|unknown=0|pn=3"}
            }, false);

        /* undo 2:1 port trade */

        records.clear();
        assertTrue(ga.canUndoBankTrade(WHEAT_2, SHEEP_1));
        tcli.bankTrade(ga, SHEEP_1, WHEAT_2);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertArrayEquals(new int[]{2, 3, 2, 3, 3}, cliPl.getResources().getAmounts(false));

        StringBuilder compares_undo_2_1 = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCBankTrade:", "|give=clay=0|ore=0|sheep=1|wheat=0|wood=0|unknown=0|get=clay=0|ore=0|sheep=0|wheat=2|wood=0|unknown=0|pn=3"}
            }, false);

        /* leave game, consolidate results */

        srv.destroyGameAndBroadcast(ga.getName(), null);
        tcli.destroy();

        StringBuilder compares = new StringBuilder();
        if (compares_4_1 != null)
        {
            compares.append("4:1 bank trade: Message mismatch: ");
            compares.append(compares_4_1);
        }
        if (compares_undo_4_1 != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Undo 4:1 bank trade: Message mismatch: ");
            compares.append(compares_undo_4_1);
        }
        if (compares_2_1 != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("2:1 port trade: Message mismatch: ");
            compares.append(compares_2_1);
        }
        if (compares_undo_2_1 != null)
        {
            if (compares.length() > 0)
                compares.append("   ");
            compares.append("Undo 2:1 port trade: Message mismatch: ");
            compares.append(compares_undo_2_1);
        }

        if (compares.length() > 0)
        {
            compares.insert(0, "For test " + CLIENT_NAME + ": ");
            System.err.println(compares);
            fail(compares.toString());
        }
    }

    /**
     * Player trade offers: Connect with 2 clients, have one offer a trade to the other,
     * send a counter-offer, first client accepts counter-offer. Also tests clear offer.
     * Then tests resource tracking when non-client trade partner has unknown resources
     * (indirectly tests {@link SOCDisplaylessPlayerClient#handlePLAYERELEMENT_numRsrc(SOCPlayer, int, int, int)}).
     * Declining a trade offer is tested by {@link TestRecorder#testTradeDecline2Clients()}.
     */
    @Test
    public void testTradeCounterAccept()
        throws IOException
    {
        assertNotNull(srv);

        // These messages are all public with no text, and should have no differences between human and robot clients.
        // We'll test all 4 combinations just in case.
        testOne_TradeCounterAccept(false, false);
        testOne_TradeCounterAccept(false, true);
        testOne_TradeCounterAccept(true, false);
        testOne_TradeCounterAccept(true, true);
    }

    private void testOne_TradeCounterAccept
        (final boolean clientAsRobot, final boolean othersAsRobot)
        throws IOException
    {
        final String nameSuffix = (clientAsRobot ? 'r' : 'h') + (othersAsRobot ? "_r" : "_h");
        final String CLIENT1_NAME = "testTrades_p3_" + nameSuffix,
            CLIENT2_NAME = "testTrades_p2_" + nameSuffix;
        final int PN_C1 = 3, PN_C2 = 2;

        final StartedTestGameObjects objs =
            TestRecorder.connectLoadJoinResumeGame
                (srv, CLIENT1_NAME, CLIENT2_NAME, PN_C2, null, true, 0, clientAsRobot, othersAsRobot);
        final DisplaylessTesterClient tcli1 = objs.tcli, tcli2 = objs.tcli2;
        final SOCGame ga = objs.gameAtServer;
        final String gaName = ga.getName();
        final SOCPlayer cli1Pl = objs.clientPlayer, cli2Pl = objs.client2Player;
        final Vector<EventEntry> records = objs.records;

        records.clear();

        /* client 1: offer trade only to client 2 */

        final boolean[] OFFERED_TO = {false, false, true, false};
        final SOCResourceSet GIVING = new SOCResourceSet(0, 1, 0, 1, 0, 0),
            GETTING = new SOCResourceSet(0, 0, 1, 0, 0, 0);
        tcli1.offerTrade(ga, new SOCTradeOffer
            (gaName, PN_C1, OFFERED_TO, GIVING, GETTING));

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        SOCTradeOffer offer = cli1Pl.getCurrentOffer();
        assertNotNull(offer);
        assertEquals(PN_C1, offer.getFrom());
        assertArrayEquals(OFFERED_TO, offer.getTo());
        assertEquals(GIVING, offer.getGiveSet());
        assertEquals(GETTING, offer.getGetSet());
        assertTrue(offer.isWaitingReplyFrom(PN_C2));

        /* client 1: clear that offer */

        tcli1.clearOffer(ga);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertNull(cli1Pl.getCurrentOffer());

        /* client 2: counter-offer */

        final boolean[] COUNTER_TO = {false, false, false, true};
        final SOCResourceSet COUNTER_GIVING = new SOCResourceSet(1, 0, 0, 0, 0, 0),
            COUNTER_GETTING = GIVING;
        tcli2.offerTrade(ga, new SOCTradeOffer
            (gaName, PN_C2, COUNTER_TO, COUNTER_GIVING, COUNTER_GETTING));

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        offer = cli2Pl.getCurrentOffer();
        assertNotNull(offer);
        assertEquals(PN_C2, offer.getFrom());
        assertArrayEquals(COUNTER_TO, offer.getTo());
        assertEquals(COUNTER_GIVING, offer.getGiveSet());
        assertEquals(COUNTER_GETTING, offer.getGetSet());
        assertTrue(offer.isWaitingReplyFrom(PN_C1));

        /* client 1: accept counter-offer */

        assertArrayEquals(new int[]{3, 3, 3, 4, 4}, cli1Pl.getResources().getAmounts(false));
        assertArrayEquals(new int[]{1, 1, 2, 0, 0}, cli2Pl.getResources().getAmounts(false));

        tcli1.acceptOffer(ga, PN_C2);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertArrayEquals
            (gaName + ": cli1 res at server after trade",
             new int[]{4, 2, 3, 3, 4}, cli1Pl.getResources().getAmounts(false));
        assertArrayEquals
            (gaName + ": cli2 res at server after trade",
             new int[]{0, 2, 2, 1, 0}, cli2Pl.getResources().getAmounts(false));
        assertNull(cli2Pl.getCurrentOffer());

        /* Test tracking unknown resources: */

        final SOCGame gaAtCli1 = tcli1.getGame(gaName), gaAtCli2 = tcli2.getGame(gaName);
        assertNotNull("found " + gaName + " at cli1", gaAtCli1);
        assertNotNull("found " + gaName + " at cli2", gaAtCli2);
        final SOCPlayer pl1AtCli1 = gaAtCli1.getPlayer(PN_C1), pl2AtCli1 = gaAtCli1.getPlayer(PN_C2),
            pl1AtCli2 = gaAtCli2.getPlayer(PN_C1), pl2AtCli2 = gaAtCli2.getPlayer(PN_C2);
        {
            final String pname1 = cli1Pl.getName(), pname2 = cli2Pl.getName();
            assertFalse(pname1.isEmpty());
            assertFalse(pname2.isEmpty());
            assertEquals("found " + pname1 + " in cli1 game", pname1, pl1AtCli1.getName());
            assertEquals("found " + pname1 + " in cli2 game", pname1, pl1AtCli2.getName());
            assertEquals("found " + pname2 + " in cli1 game", pname2, pl2AtCli1.getName());
            assertEquals("found " + pname2 + " in cli2 game", pname2, pl2AtCli2.getName());
        }

        /* set up known and unknown resources at clients and server */

        // PN_C1 will offer to give 1 ore, receive 2 wood from PN_C2:
        // PN_C1: Start with 1 clay 3 ore, or 1 clay 3 unknown; trade away 1 non-clay resource (ore),
        //        then afterwards have (1 clay 2 ore, or 1 clay 2 unknown) + the received 2 wood
        // PN_C2: Start with 2 sheep 2 wood, or 1 wood 3 unknown; trade away 2 wood resources,
        //        so will convert all to 4 unknown before trade,
        //        then afterwards have (2 sheep, or 2 unknown) + the received 1 ore resource
        final SOCResourceSet c1Known = new SOCResourceSet(1, 3, 0, 0, 0, 0),
            c2Known = new SOCResourceSet(0, 0, 2, 0, 2, 0);
        cli1Pl.getResources().setAmounts(c1Known);
        cli2Pl.getResources().setAmounts(c2Known);
        pl1AtCli1.getResources().setAmounts(c1Known);
        pl2AtCli2.getResources().setAmounts(c2Known);
        pl1AtCli2.getResources().setAmounts(new SOCResourceSet(1, 0, 0, 0, 0, 3));
        pl2AtCli1.getResources().setAmounts(new SOCResourceSet(0, 0, 0, 0, 1, 3));

        /* client 1: make offer only to client 2 */

        final SOCResourceSet GIVING_2 = new SOCResourceSet(0, 1, 0, 0, 0, 0),
            GETTING_2 = new SOCResourceSet(0, 0, 0, 0, 2, 0);
        tcli1.offerTrade(ga, new SOCTradeOffer
            (gaName, PN_C1, OFFERED_TO, GIVING_2, GETTING_2));

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        offer = cli1Pl.getCurrentOffer();
        assertNotNull(offer);
        assertEquals(PN_C1, offer.getFrom());
        assertArrayEquals(OFFERED_TO, offer.getTo());
        assertEquals(GIVING_2, offer.getGiveSet());
        assertEquals(GETTING_2, offer.getGetSet());
        assertTrue(offer.isWaitingReplyFrom(PN_C2));

        /* client 2: accept offer */

        tcli2.acceptOffer(ga, PN_C1);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        // players at server:
        assertArrayEquals
            (gaName + ": cli1 res at server after trade",
             new int[]{1, 2, 0, 0, 2}, cli1Pl.getResources().getAmounts(false));
        assertArrayEquals
            (gaName + ": cli2 res at server after trade",
             new int[]{0, 1, 2, 0, 0}, cli2Pl.getResources().getAmounts(false));
        assertNull(cli1Pl.getCurrentOffer());
        // at tcli1:
        assertArrayEquals
            (gaName + ": cli1 res at cli1 after trade",
             new int[]{1, 2, 0, 0, 2}, pl1AtCli1.getResources().getAmounts(false));
        assertArrayEquals
            (gaName + ": cli2 res at cli1 after trade",
             new int[]{0, 1, 0, 0, 0, 2}, pl2AtCli1.getResources().getAmounts(true));
        // at tcli2:
        assertArrayEquals
            (gaName + ": cli1 res at cli2 after trade",
             new int[]{1, 0, 0, 0, 2, 2}, pl1AtCli2.getResources().getAmounts(true));
        assertArrayEquals
            (gaName + ": cli2 res at cli2 after trade",
             new int[]{0, 1, 2, 0, 0}, pl2AtCli2.getResources().getAmounts(false));

        StringBuilder compares = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                // usual trade
                {"all:SOCMakeOffer:", "|from=" + PN_C1
                 + "|to=false,false,true,false|give=clay=0|ore=1|sheep=0|wheat=1|wood=0|unknown=0|get=clay=0|ore=0|sheep=1|wheat=0|wood=0|unknown=0"},
                {"all:SOCClearTradeMsg:", "|playerNumber=-1"},
                {"all:SOCClearOffer:", "|playerNumber=" + PN_C1},
                {"all:SOCClearTradeMsg:", "|playerNumber=-1"},
                {"all:SOCMakeOffer:", "|from=" + PN_C2
                 + "|to=false,false,false,true|give=clay=1|ore=0|sheep=0|wheat=0|wood=0|unknown=0|get=clay=0|ore=1|sheep=0|wheat=1|wood=0|unknown=0"},
                {"all:SOCClearTradeMsg:", "|playerNumber=-1"},
                {"all:SOCAcceptOffer:", "|accepting=" + PN_C1 + "|offering=" + PN_C2
                    + "|toAccepting=clay=1|ore=0|sheep=0|wheat=0|wood=0|unknown=0|toOffering=clay=0|ore=1|sheep=0|wheat=1|wood=0|unknown=0"},
                {"all:SOCClearOffer:", "|playerNumber=-1"},

                // tracking unknown resources
                {"all:SOCMakeOffer:", "|from=" + PN_C1
                 + "|to=false,false,true,false|give=clay=0|ore=1|sheep=0|wheat=0|wood=0|unknown=0|get=clay=0|ore=0|sheep=0|wheat=0|wood=2|unknown=0"},
                {"all:SOCClearTradeMsg:", "|playerNumber=-1"},
                {"all:SOCAcceptOffer:", "|accepting=" + PN_C2 + "|offering=" + PN_C1
                 + "|toAccepting=clay=0|ore=1|sheep=0|wheat=0|wood=0|unknown=0|toOffering=clay=0|ore=0|sheep=0|wheat=0|wood=2|unknown=0"},
                {"all:SOCClearOffer:", "|playerNumber=-1"},
            }, false);

        /* leave game, check results */

        srv.destroyGameAndBroadcast(ga.getName(), null);
        tcli1.destroy();
        tcli2.destroy();

        if (compares != null)
        {
            compares.append("testTradeCounterAccept(" + nameSuffix + "): Message mismatch: ");
            compares.append(compares);

            System.err.println(compares);
            fail(compares.toString());
        }
    }

    /**
     * Test End Turn: Connect 2 clients, seated next to each other.
     * Current player client 1 will end turn, then test will check messages before client 2 rolls.
     */
    @Test
    public void testEndTurn()
        throws IOException
    {
        assertNotNull(srv);

        // These messages are all public with no text, and should have no differences between human and robot clients.
        // We'll test all 4 combinations just in case.
        testOne_EndTurn(false, false);
        testOne_EndTurn(false, true);
        testOne_EndTurn(true, false);
        testOne_EndTurn(true, true);
    }

    private void testOne_EndTurn
        (final boolean clientAsRobot, final boolean othersAsRobot)
        throws IOException
    {
        final String nameSuffix = (clientAsRobot ? 'r' : 'h') + (othersAsRobot ? "_r" : "_h");
        final String CLIENT1_NAME = "testEndTurn_p3_" + nameSuffix, CLIENT2_NAME = "testEndTurn_p1_" + nameSuffix;
        final int PN_C2 = 1;

        final StartedTestGameObjects objs =
            TestRecorder.connectLoadJoinResumeGame
                (srv, CLIENT1_NAME, CLIENT2_NAME, PN_C2, null, true, 0, clientAsRobot, othersAsRobot);
        final DisplaylessTesterClient tcli1 = objs.tcli, tcli2 = objs.tcli2;
        final SOCGame ga = objs.gameAtServer;
        final Vector<EventEntry> records = objs.records;

        records.clear();

        /* pn 3 client 1: end turn */

        tcli1.endTurn(ga);

        try { Thread.sleep(90); }
        catch(InterruptedException e) {}

        /* pn 1 client 2 is next player, since pn 0 is vacant */

        assertEquals(PN_C2, ga.getCurrentPlayerNumber());
        assertEquals(SOCGame.ROLL_OR_CARD, ga.getGameState());

        // we don't need client 2 to do anything;
        // it's here so that a robot player won't take action
        // before we've captured and compared the message sequence

        StringBuilder compares = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCClearOffer:", "|playerNumber=-1"},
                {"all:SOCTurn:", "|playerNumber=1|gameState=15"},
                {"all:SOCRollDicePrompt:", "|playerNumber=1"}
            }, false);

        /* leave game, check results */

        srv.destroyGameAndBroadcast(ga.getName(), null);
        tcli1.destroy();
        tcli2.destroy();

        if (compares != null)
        {
            compares.append("testEndTurn(" + nameSuffix + "): Message mismatch: ");
            compares.append(compares);

            System.err.println(compares);
            fail(compares.toString());
        }
    }

    /**
     * Test asking for Special Building Phase (SBP) in a 6-player game.
     * Uses same savegame artifact {@code "test6p-sbp"} as {@link TestLoadgame#testLoad6PlayerSBP()}.
     */
    @Test
    public void test6pAskSpecialBuild()
        throws IOException
    {
        assertNotNull(srv);

        testOne_6pAskSpecialBuild(false, false);
        testOne_6pAskSpecialBuild(false, true);
        testOne_6pAskSpecialBuild(true, false);
        testOne_6pAskSpecialBuild(true, true);
    }

    private void testOne_6pAskSpecialBuild
        (final boolean clientAsRobot, final boolean othersAsRobot)
        throws IOException
    {
        final String nameSuffix = (clientAsRobot ? 'r' : 'h') + (othersAsRobot ? "_r" : "_h");
        final String CLIENT1_NAME = "testAskSBP_p5_" + nameSuffix, CLIENT2_NAME = "testAskSBP_p2_" + nameSuffix;
        final int PN_CLI = 5, PN_C2 = 2;

        final SavedGameModel sgm = TestLoadgame.load("test6p-sbp.game.json", srv);

        // Test setup, slightly different than what's in artifact for TestLoadgame.testLoad6PlayerSBP:
        sgm.gameState = SOCGame.PLAY1;
        sgm.playerSeats[1].isRobot = true;  // needed for resumeLoadedGame
        sgm.getGame().setCurrentPlayerNumber(PN_C2);

        final StartedTestGameObjects objs =
            TestRecorder.connectLoadJoinResumeGame
                (srv, CLIENT1_NAME, CLIENT2_NAME, PN_C2, sgm, false, 0, clientAsRobot, othersAsRobot);
        final DisplaylessTesterClient tcli1 = objs.tcli, tcli2 = objs.tcli2;
        final SOCGame ga = objs.gameAtServer;
        final SOCPlayer clientPlayer = objs.clientPlayer;
        assertEquals(PN_CLI, clientPlayer.getPlayerNumber());
        final Vector<EventEntry> records = objs.records;

        // Verify current player and basics of game: same as in TestLoadgame.testLoad6PlayerSBP.
        // Copying that code instead of calling it, to ensure it's still checked here if that check changes.
        // Also clears all players' Special Building flag to set up for test.
        assertEquals("game name", "test6p-sbp", sgm.gameName);
        assertEquals(PN_C2, ga.getCurrentPlayerNumber());
        assertEquals("should be 6 players", 6, sgm.playerSeats.length);
        assertEquals("should be 6 players", 6, ga.maxPlayers);
        final boolean[] EXPECT_BOT = {false, true, true, true, true, false};
        for (int pn = 0; pn < ga.maxPlayers; ++pn)
        {
            boolean expectVacant = (pn == 0);
            assertEquals("players[" + pn + "]", expectVacant, ga.isSeatVacant(pn));
            if (expectVacant)
                continue;
            assertEquals("isRobot[" + pn + "]", EXPECT_BOT[pn], sgm.playerSeats[pn].isRobot);

            ga.getPlayer(pn).setAskedSpecialBuild(false);
        }
        clientPlayer.setSpecialBuilt(false);

        // since current player is client 2, not a bot,
        // when game resumes it'll wait to take action
        // and client 1 has time to ask for SBP.

        TestRecorder.resumeLoadedGame(ga, srv, objs.tcliConn);
        assertEquals(SOCGame.PLAY1, ga.getGameState());
        assertFalse(clientPlayer.hasSpecialBuilt());

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}

        records.clear();

        // cli1 ask SBP

        tcli1.buildRequest(ga, -1);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertTrue("asked special building?", clientPlayer.hasAskedSpecialBuild());

        // cli2 end turn

        tcli2.endTurn(ga);

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertEquals(SOCGame.SPECIAL_BUILDING, ga.getGameState());
        assertEquals(PN_CLI, ga.getCurrentPlayerNumber());

        // cli1 try build something during SBP

        SOCBoard board = ga.getBoard();
        assertNull("no road already at 0x82", board.roadOrShipAtEdge(0x82));
        tcli1.putPiece(ga, new SOCRoad(clientPlayer, 0x82, board));

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}
        assertTrue("built road at 0x82", board.roadOrShipAtEdge(0x82) instanceof SOCRoad);

        // check results

        StringBuilder compares = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCPlayerElement:game=", "|playerNum=5|actionType=SET|elementType=16|amount=1"},  // ASK_SPECIAL_BUILD
                {"all:SOCClearOffer:game=", "|playerNumber=-1"},
                {"all:SOCTurn:game=", "|playerNumber=5|gameState=100"},
                {"all:SOCPlayerElements:game=", "|playerNum=5|actionType=LOSE|e1=1,e5=1"},
                {"all:SOCGameServerText:game=", "|text="+ CLIENT1_NAME + " built a road."},
                {"all:SOCPutPiece:game=", "|playerNumber=5|pieceType=0|coord=82"},
                {"all:SOCGameState:game=", "|state=100"}
            }, false);

        /* leave game, check results */

        srv.destroyGameAndBroadcast(ga.getName(), null);
        tcli1.destroy();
        tcli2.destroy();

        if (compares != null)
        {
            compares.append("testAskSBP(" + nameSuffix + "): Message mismatch: ");
            compares.append(compares);

            System.err.println(compares);
            fail(compares.toString());
        }
    }

    /**
     * Test Win Game: With client player win, another player win.
     */
    @Test
    public void testWinGame()
        throws IOException
    {
        assertNotNull(srv);

        for (boolean clientWin : new boolean[]{true, false})
        {
            testOne_WinGame(clientWin, false, false);
            testOne_WinGame(clientWin, false, true);
            testOne_WinGame(clientWin, true, false);
            testOne_WinGame(clientWin, true, true);
        }
    }

    private void testOne_WinGame
        (final boolean clientWin, final boolean clientAsRobot, final boolean othersAsRobot)
        throws IOException
    {
        final String nameSuffix =
            (clientWin ? "cw_" : "ow_") + (clientAsRobot ? 'r' : 'h') + (othersAsRobot ? "_r" : "_h");
        final String CLIENT_NAME = "testWinGame_" + nameSuffix,
           OTHER_WIN_CLIENT_NAME = (clientWin) ? null : "testWinOther_" + nameSuffix;
        final int PN_WIN = ((clientWin) ? 3 : 2),
            PN_OTHER_NONWIN_PLAYER = 1;
        final int SETTLE_NODE = (clientWin) ? 0x60a : 0x403;

        final StartedTestGameObjects objs =
            TestRecorder.connectLoadJoinResumeGame
                (srv, CLIENT_NAME, OTHER_WIN_CLIENT_NAME, (clientWin) ? 0 : PN_WIN,
                 null, false, 0, clientAsRobot, othersAsRobot);
        final DisplaylessTesterClient tcli = objs.tcli, tcli2 = objs.tcli2;
        if (! clientWin)
        {
            assertNotNull(tcli2);
            if (othersAsRobot)
                objs.tcli2Conn.setI18NStringManager(null, null);
        }
        final SOCGame ga = objs.gameAtServer;
        final SOCPlayer plWin = ga.getPlayer(PN_WIN);
        final String plName = plWin.getName();
        if (! clientWin)
            plWin.setRobotFlag(othersAsRobot, othersAsRobot);
        final Vector<EventEntry> records = objs.records;

        /* prep: change game data and resume */

        plWin.setNumKnights(3);
        ga.setPlayerWithLargestArmy(plWin);
        assertEquals(4, plWin.getTotalVP());

        while (plWin.getTotalVP() < 9)
            plWin.getInventory().addDevCard
                (1, SOCInventory.OLD, SOCDevCardConstants.UNIV);
        // for end-of-game messages, other player gets one too
        ga.getPlayer(PN_OTHER_NONWIN_PLAYER).getInventory().addDevCard
            (1, SOCInventory.OLD, SOCDevCardConstants.CAP);

        ga.setCurrentPlayerNumber(PN_WIN);
        plWin.getResources().setAmounts(SOCSettlement.COST);

        TestRecorder.resumeLoadedGame(ga, srv, objs.tcliConn);
        assertEquals(PN_WIN, ga.getCurrentPlayerNumber());
        assertEquals(SOCGame.PLAY1, ga.getGameState());

        try { Thread.sleep(60); }
        catch(InterruptedException e) {}

        records.clear();

        /* build winning settlement */

        final SOCSettlement sett = new SOCSettlement(plWin, SETTLE_NODE, ga.getBoard());
        if (clientWin)
            tcli.putPiece(ga, sett);
        else
            tcli2.putPiece(ga, sett);
        try { Thread.sleep(60); }
        catch(InterruptedException e) {}

        assertEquals(10, plWin.getTotalVP());
        StringBuilder compares = TestRecorder.compareRecordsToExpected
            (records, new String[][]
            {
                {"all:SOCPlayerElements:", "|playerNum=" + PN_WIN + "|actionType=LOSE|e1=1,e3=1,e4=1,e5=1"},
                {"all:SOCGameServerText:", "|text=" + plName + " built a settlement."},
                {"all:SOCPutPiece:", "|playerNumber=" + PN_WIN + "|pieceType=1|coord=" + Integer.toHexString(SETTLE_NODE)},
                {"all:SOCGameElements:", "|e4=" + PN_WIN},  // CURRENT_PLAYER
                {"all:SOCGameState:", "|state=1000"},
                {"all:SOCGameServerText:", "|text=>>> " + plName + " has won the game with 10 points."},
                {"all:SOCDevCardAction:", "|playerNum=" + PN_OTHER_NONWIN_PLAYER + "|actionType=ADD_OLD|cardType=4"},
                {"all:SOCDevCardAction:", "|playerNum=" + PN_WIN + "|actionType=ADD_OLD|cardTypes=[6, 6, 6, 6, 6]"},
                {"all:SOCGameStats:",
                   ((clientWin) ? "|0|3|2|10" : "|0|3|10|2")
                   + ((othersAsRobot) ? "|false|true|true" : "|false|false|false")
                   + ((clientAsRobot) ? "|true" : "|false") },
                {"all:SOCGameServerText:", "|text=This game was 2 rounds, and took "},
                ((othersAsRobot) ? null : new String[]{"p1:SOCPlayerStats:", "|p=1|p=1|p=1|p=1|p=2|p=0"}),
                ((othersAsRobot) ? null : new String[]{"p1:SOCPlayerStats:", "|p=2|p=10|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0"}),
                ((othersAsRobot) ? null : new String[]{"p2:SOCPlayerStats:", "|p=1|p=1|p=1|p=1|p=0|p=0"}),
                ((othersAsRobot) ? null : new String[]{"p2:SOCPlayerStats:", "|p=2|p=10|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0"}),
                ((clientAsRobot) ? null : new String[]{"p3:SOCPlayerStats:", "|p=1|p=1|p=0|p=0|p=2|p=2"}),
                ((clientAsRobot) ? null : new String[]{"p3:SOCPlayerStats:", "|p=2|p=10|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0|p=0"})
            }, false);

        /* leave game, check results */

        srv.destroyGameAndBroadcast(ga.getName(), null);
        tcli.destroy();
        if (tcli2 != null)
            tcli2.destroy();

        if (compares != null)
        {
            compares.append("testWinGame(" + nameSuffix + "): Message mismatch: ");
            compares.append(compares);

            System.err.println(compares);
            fail(compares.toString());
        }
    }

}
