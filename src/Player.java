import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import static java.util.Comparator.comparingInt;

/**
 * Send your busters out into the fog to trap ghosts and bring them home!
 **/
class Player {

    public static int MAX_WIDTH = 16000;
    public static int MAX_HEIGTH = 9000;

    private static final int VISION_RANGE = 2200; //real range

    public static final int MOVE_RANGE = 800;

    public static final int CASE_SIZE = 500;

    public static final int NB_CASE_VISION = VISION_RANGE / CASE_SIZE;

    public static final int NB_HEIGTH = MAX_HEIGTH / CASE_SIZE;
    public static final int NB_WIDTH = MAX_WIDTH / CASE_SIZE;


    private static final int RELEASE_RANGE = 1600;
    public static int BUST_MAX = 1760;
    public static int BUST_MIN = 900;
    public static int STUN_RANGE = 1760;

    public static int TEAM_0_X = 0;
    public static int TEAM_0_Y = 0;

    public static int TEAM_1_X = 16000;
    public static int TEAM_1_Y = 9000;

    public static int STATE_BUSTER_NO_GHOST = 0; // Buster ne transportant pas de fantôme.
    public static int STATE_BUSTER_HAS_GHOST = 1; // Buster transportant un fantôme.
    public static int STATE_STUN = 2; // Buster assommé.
    public static int STATE_TARGET_GHOST = 3; // Buster visant un fantome.

    public static SortedSet<Hunter> myUnits;
    public static Map<Integer, Hunter> myUnitsMap;

    public static SortedSet<Hunter> enemyList;
    public static Map<Integer, Hunter> enemyUnit;
    public static Collection<Hunter> seenEnnemy = new ArrayList<>();


    public static Map<Integer, Ghosts> ghosts;

    public static Collection<Ghosts> seenGhost = new ArrayList<>();

    public static Case[][] caseMap = new Case[NB_WIDTH][NB_HEIGTH];
    public static Collection<Case> allCase = new ArrayList<>(NB_WIDTH * NB_HEIGTH);
    public static Case myTeamCase;

    private static ExecutorService EXECUTORS;

    public static int turn = 0;

    public static void main(String args[]) {
        Scanner in = new Scanner(System.in);
        int bustersPerPlayer = in.nextInt(); // the amount of busters you control
        int ghostCount = in.nextInt(); // the amount of ghosts on the map
        int myTeamId = in.nextInt(); // if this is 0, your base is on the top left of the map, if it is one, on the bottom right

        myUnits = new TreeSet<>();
        enemyList = new TreeSet<>();

        myUnitsMap = new HashMap<>();
        enemyUnit = new HashMap<>();

        ghosts = new HashMap<>();

        EXECUTORS = Executors.newFixedThreadPool(bustersPerPlayer);

        initCases();
        initMyTeamCase(myTeamId);
        initGhost(ghostCount);

        Strategie<Hunter> strategies[] = asArray(new StunEnemyWithGhostStrategy());

        // game loop
        while (true) {

            resetCase();
            seenGhost.clear();
            seenEnnemy.clear();

            int entities = in.nextInt(); // the number of busters and ghosts visible to you
            for (int i = 0; i < entities; i++) {
                int entityId = in.nextInt(); // buster id or ghost id
                int x = in.nextInt();
                int y = in.nextInt(); // position of this buster / ghost
                int entityType = in.nextInt(); // the team id if it is a buster, -1 if it is a ghost.
                int state = in.nextInt(); // For busters: 0=idle, 1=carrying a ghost.
                int value = in.nextInt(); // For busters: Ghost id being carried. For ghosts: number of busters attempting to trap this ghost.

                majEntity(myTeamId, entityId, x, y, entityType, state, value);
            }

            majPath(bustersPerPlayer);
            majVisited();
            majGhost();

            int i = 0;



            //recherche des actions
            actionStunned();
            actionHasGhost();
//            actionStunEnemyWithGhost();
            while (i < strategies.length) {
                Strategie<Hunter> strategy = strategies[i];
                if(strategy.runThisStrategie()) {
                    myUnits.stream().filter((hunter -> hunter.action == null))
                            .forEach(strategy::accept);
                }
            }
            actionAlreadyTarget();
            actionBust();
            actionMoveToGhostSeen();
            actionMoveToPreviousSeen();
            actyionMoveToUnseeMap();

            myUnits.forEach((hunter) -> {
                System.out.println(hunter.action);
            });

            turn++;
        }
    }

    private static Strategie<Hunter>[] asArray(Strategie<Hunter>... strategiies) {
        return strategiies;
    }


    private static void initGhost(int ghostCount) {
        for (int i = 0; i < ghostCount; i++) {
            ghosts.put(i, new Ghosts(i));
        }
    }

    private static void initMyTeamCase(int myTeamId) {
        myTeamCase = new Case();
        if (myTeamId == 0) {
            myTeamCase.x = 0;
            myTeamCase.y = 0;
        } else {
            myTeamCase.x = MAX_WIDTH;
            myTeamCase.y = MAX_HEIGTH;
        }
    }

    private static void actionStunEnemyWithGhost() {
        if (seenEnnemy.isEmpty()) {
            return;
        }

        if (seenEnnemy.stream().allMatch((e) -> e.state == STATE_STUN)) {
            return;
        }

        myUnits.stream()
                .filter((hunter -> hunter.action == null && hunter.lastStunTurned + 20 < turn))
                .forEach((hunter) -> {
                    Optional<Hunter> stunableEnemy = seenEnnemy.stream()
                            .filter((e) -> e.state == STATE_BUSTER_HAS_GHOST && e.distance(hunter) < STUN_RANGE)
                            .findFirst();
                    stunableEnemy.ifPresent((e) -> {
                        hunter.stun(e);
                    });
                });
    }

    private static void actionStunEnemyNearLowGhostHp() {
        if (seenEnnemy.isEmpty() || seenGhost.isEmpty()) {
            return;
        }

        List<Hunter> noStunnedEnnemy = seenEnnemy.stream().filter((e) -> e.state == STATE_STUN).collect(Collectors.toList());
        if (noStunnedEnnemy.isEmpty()) {
            return;
        }
        List<Ghosts> lowHpGhost = seenGhost.stream().filter((g) -> g.state < 10).collect(Collectors.toList());

        if (lowHpGhost.isEmpty()) {
            return;
        }

        Set<Hunter> enemyToStun = lowHpGhost.stream()
                .flatMap((g) -> noStunnedEnnemy.stream()
                        .filter((e) -> g.distance(e) < BUST_MAX)
                ).collect(Collectors.toSet());

        if (enemyToStun.isEmpty()) {
            return;
        }

        myUnits.stream()
                .filter((hunter -> hunter.action == null && hunter.lastStunTurned + 20 < turn))
                .forEach((hunter) -> {
                    Optional<Hunter> stunableEnemy = enemyToStun.stream()
                            .filter((e) -> e.distance(hunter) < STUN_RANGE)
                            .findFirst();
                    stunableEnemy.ifPresent((e) -> {
                        hunter.stun(e);
                    });
                });


    }


    private static void actyionMoveToUnseeMap() {
        myUnits.stream().filter((hunter -> hunter.action == null))
                .forEach((hunter) -> {
                    Optional<Case> notvisitedCase = getNotVisited(hunter);

                    Case aCase = notvisitedCase.orElseGet(() -> allCase.stream().findAny().get());

                    aCase.hasHunter = true;
                    aCase.neighbourg.forEach((c) -> c.hasHunter = true);
                    LOG.debug(hunter.entityId + ": move to useen " + aCase);
                    hunter.move(aCase);


                });
    }

    private static void actionMoveToPreviousSeen() {
        myUnits.stream().filter((hunter -> hunter.action == null))
                .forEach((hunter) -> {
                    // va vers un fantomes deja localise
                    Optional<Ghosts> ghost = ghosts.values().stream()
                            .filter((g) -> !g.captured && g.huntedBy == null && g.casePos != null)
                            .sorted(
                                    comparingInt((ToIntFunction<Ghosts>) (g) -> g.state)
                                            .thenComparingDouble((g) -> g.casePos.distance(hunter))
                            )
                            .findFirst();

                    if (ghost.isPresent()) {
                        Ghosts g = ghost.get();
                        LOG.debug(hunter.entityId + ": move to previously seen " + g.entityId);
                        hunter.move(g);
                        g.huntedBy = hunter;
                    }
                });
    }

    private static void actionMoveToGhostSeen() {
        myUnits.stream().filter((hunter -> hunter.action == null))
                .forEach((hunter) -> {
                    Set<Ghosts> ghostsNoCapturedNoHunted = seenGhost.stream()
                            .filter((g) -> !g.captured && g.huntedBy == null).collect(Collectors.toSet());
                    Optional<Ghosts> ghost = ghostsNoCapturedNoHunted.stream()
                            .filter((g) -> g.distance(hunter) <= VISION_RANGE)
                            .sorted(comparingInt((g) -> g.state))
                            .findFirst();
                    if (ghost.isPresent()) {
                        Ghosts g = ghost.get();
                        LOG.debug(hunter.entityId + ": move to seen " + g.entityId);
                        hunter.move(g);
                        g.huntedBy = hunter;
                    }
                });
    }

    private static void actionBust() {
        myUnits.stream().filter((hunter -> hunter.action == null))
                .forEach((hunter) -> {

                    Set<Ghosts> ghostsNoCapturedNoHunted = seenGhost.stream()
                            .filter((g) -> !g.captured && g.huntedBy == null).collect(Collectors.toSet());

                    Optional<Ghosts> ghost = ghostsNoCapturedNoHunted
                            .stream()
                            .filter((g) -> {
                                double distance = g.distance(hunter);
                                return distance <= BUST_MAX && distance >= BUST_MIN;
                            })
                            .sorted(comparingInt((g) -> g.state))
                            .findFirst();
                    if (ghost.isPresent()) {
                        Ghosts g = ghost.get();
                        LOG.debug(hunter.entityId + ": bust " + g.entityId);
                        hunter.bust(g);
                        g.huntedBy = hunter;
                    }
                });
    }

    private static void actionAlreadyTarget() {
        myUnits.stream().filter((hunter -> hunter.action == null))
                .forEach((hunter) -> {

                    Optional<Ghosts> ghost = seenGhost.stream()
                            .filter((g) -> !g.captured && g.huntedBy == hunter)
                            .findFirst();
                    if (ghost.isPresent()) {
                        Ghosts g = ghost.get();
                        double distance = g.distance(hunter);
                        if (distance <= BUST_MAX && distance >= BUST_MIN) {
                            LOG.debug(hunter.entityId + ": bust my previous unted " + g.entityId);
                            hunter.bust(g);
                        } else {
                            LOG.debug(hunter.entityId + ": move to my previous unted " + g.entityId);
                            hunter.move(g);
                        }
                    }
                });
    }

    private static void actionHasGhost() {
        myUnits.stream().filter((hunter -> hunter.action == null))
                .forEach((hunter) -> {
                    if (hunter.state == STATE_BUSTER_HAS_GHOST) {
                        if (hunter.distance(myTeamCase) <= RELEASE_RANGE) {
                            hunter.release();
                            ghosts.get(hunter.value).captured = true;
                            LOG.debug(hunter.entityId + ": release my ghost");
                        } else {
                            //strun defensif
                            Optional<Hunter> stunableEnemy = seenEnnemy.stream()
                                    .filter((e) -> e.state != STATE_STUN && e.distance(hunter) < STUN_RANGE)
                                    .findFirst();
                            if (hunter.lastStunTurned + 20 < turn && stunableEnemy.isPresent()) {
                                hunter.stun(stunableEnemy.get());
                            } else {
                                hunter.move(myTeamCase);
                                LOG.debug(hunter.entityId + ": back to home");
                            }
                        }
                    }
                });
    }

    private static void actionStunned() {
        myUnits.forEach((hunter) -> {
            if (hunter.state == STATE_STUN) {
                hunter.move(hunter);
                LOG.debug(hunter.entityId + ":stunned");
            }
        });
    }

    /**
     * Si un ghost devrait etre dans une zone visible mais qu'il n'y est pas,
     * on reset sa case car ca veut dire que soit il a bougé, soit il a ete capturer
     */
    private static void majGhost() {
        ghosts.values().forEach((g) -> {
            if (g.casePos != null && g.casePos.seenThisturn && !seenGhost.contains(g)) {
                g.casePos = null;
                g.huntedBy = null;
            }
        });

    }

    /**
     * Mise a jour des case visité afin de savoir ou aller si no ne sait pas ou sont les fantomes.
     */
    private static void majVisited() {
        myUnits.forEach((u) -> {
            Queue<Case> queues = new ArrayDeque<>();
            queues.add(u.casePos);
            Set<Case> allVisited = new HashSet<>();
            int entityId = u.entityId;
            while (!queues.isEmpty()) {
                Case c = queues.poll();
                allVisited.add(c);
                c.visited = true;
                c.seenThisturn = true;
                c.neighbourg.forEach((n) -> {
                    Integer nValue = n.valueById.get(entityId);
                    if (!allVisited.contains(n) && nValue <= NB_CASE_VISION) {
                        queues.add(n);
                    }
                });
            }

        });
    }

    /**
     * Pour chacune de ses unités, on met a jour les chemin des cases.
     */
    private static void majPath(int bustersPerPlayer) {
        CountDownLatch latch = new CountDownLatch(bustersPerPlayer);
        myUnits.forEach((u) -> {
            int entityId = u.entityId;
            u.casePos.valueById.put(entityId, 0);
            EXECUTORS.submit(new PathCalculator<>(
                    u.casePos,
                    (c) -> c.valueById.getOrDefault(entityId, Integer.MAX_VALUE),
                    (c, v) -> c.valueById.put(entityId, v),
                    latch));

        });
        //wait all patch calculated
        try {
            latch.await(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            System.err.println("InterruptedException " + e);
        }
    }

    private static Optional<Case> getNotVisited(Hunter hunter) {
        return allCase.stream()
                .filter((c) -> !c.visited && !c.hasHunter)
                .sorted(comparingInt((c) -> c.valueById.get(hunter.entityId)))
                .findFirst();
    }

    private static void majEntity(int myTeamId, int entityId, int x, int y, int entityType, int state, int value) {
        Entity e;

        Case aCase = caseMap[x != MAX_WIDTH ? x / Player.CASE_SIZE : NB_WIDTH - 1][y != MAX_HEIGTH ? y / Player.CASE_SIZE : NB_HEIGTH - 1];
        if (entityType == -1) {
            e = ghosts.get(entityId);
            seenGhost.add((Ghosts) e);
        } else if (myTeamId == entityType) {
            e = myUnitsMap.get(entityId);
            if (e == null) {
                e = new Hunter(entityId);
                myUnits.add((Hunter) e);
                myUnitsMap.put(entityId, (Hunter) e);
            }
            ((Hunter) e).action = null;
        } else {
            e = enemyUnit.get(entityId);
            if (e == null) {
                e = new Hunter(entityId);
                enemyList.add((Hunter) e);
                enemyUnit.put(entityId, (Hunter) e);
            }
            seenEnnemy.add((Hunter) e);
        }
        //LOG.debug(e);
//        if (!(e instanceof Ghosts) || e.casePos == null) {
        e.casePos = aCase;
//        }
        e.x = x;
        e.y = y;
        e.state = state;
        e.value = value;
    }

    private static void resetCase() {
        for (int j = 0; j < NB_HEIGTH; j++) {
            for (int i = 0; i < NB_WIDTH; i++) {
                caseMap[i][j].hasHunter = false;
                caseMap[i][j].seenThisturn = false;
                caseMap[i][j].valueById.clear();
            }
        }
    }

    private static void initCases() {
        LOG.debug("NB_HEIGTH=" + NB_HEIGTH);
        LOG.debug("NB_WIDTH=" + NB_WIDTH);
        for (int j = 0; j < NB_HEIGTH; j++) {
            for (int i = 0; i < NB_WIDTH; i++) {
                caseMap[i][j] = new Case();
                caseMap[i][j].x = i * CASE_SIZE;
                caseMap[i][j].y = j * CASE_SIZE;
                allCase.add(caseMap[i][j]);
            }
        }

        for (int j = 0; j < NB_HEIGTH; j++) {
            for (int i = 0; i < NB_WIDTH; i++) {
                Case currentCase = caseMap[i][j];
                if (j > 0) {
                    currentCase.neighbourg.add(caseMap[i][j - 1]);
                    if (i > 0) {
                        currentCase.neighbourg.add(caseMap[i - 1][j - 1]);
                    }
                    if ((i + 1) < NB_WIDTH) {
                        currentCase.neighbourg.add(caseMap[i + 1][j - 1]);
                    }
                }
                if ((j + 1) < NB_HEIGTH) {
                    currentCase.neighbourg.add(caseMap[i][j + 1]);
                    if (i > 0) {
                        currentCase.neighbourg.add(caseMap[i - 1][j + 1]);
                    }
                    if ((i + 1) < NB_WIDTH) {
                        currentCase.neighbourg.add(caseMap[i + 1][j + 1]);
                    }
                }
                if (i > 0) {
                    currentCase.neighbourg.add(caseMap[i - 1][j]);
                }
                if ((i + 1) < NB_WIDTH) {
                    currentCase.neighbourg.add(caseMap[i + 1][j]);
                }
            }
        }

    }
}


class Ghosts extends Entity {

    public Hunter huntedBy;
    public boolean captured = false;

    public Ghosts(int id) {
        this.entityId = id;
    }

    public Ghosts getSymetric() {
        Ghosts g = new Ghosts(-1);
        g.x = Player.MAX_WIDTH - this.x;
        g.y = Player.MAX_HEIGTH - this.y;
        return g;
    }

}

class Hunter extends Entity {
    public String action = null;
    public int lastStunTurned = 0;

    public Hunter(int id) {
        this.entityId = id;
    }

    public void bust(Ghosts g) {
        this.bust(g.entityId);
    }

    public void bust(int idGhost) {
        this.action = "BUST " + idGhost;
    }

    public void release() {
        this.action = "RELEASE";
    }

    public void move(Position pos) {
        this.move(pos.x, pos.y);
    }

    public void move(int x, int y) {
        this.action = "MOVE " + x + " " + y;
    }

    public void stun(Hunter enemy) {
        this.action = "STUN " + enemy.entityId;
        this.lastStunTurned = Player.turn;
    }

}


abstract class Entity extends Position implements Comparable<Entity> {
    public int entityId; // buster id or ghost id
    public int entityType; // the team id if it is a buster, -1 if it is a ghost.
    public int entityRole; // -1 for ghosts, 0 for the HUNTER, 1 for the GHOST CATCHER and 2 for the SUPPORT
    public int state; // For busters: 0=idle, 1=carrying a ghost. For ghosts: remaining stamina points.
    public int value; // For busters: Ghost id being carried/busted or number of turns left when stunned. For ghosts: number of busters attempting to trap this ghost.
    public Case casePos;


    @Override
    public int hashCode() {
        return entityId;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Ghosts) {
            return this.entityId == ((Ghosts) obj).entityId;
        } else {
            return false;
        }
    }

    @Override
    public int compareTo(Entity o) {
        return this.entityId - o.entityId;
    }

    @Override
    public String toString() {
        return "Entity{" +
                "entityId=" + entityId +
                ", entityType=" + entityType +
                ", entityRole=" + entityRole +
                ", state=" + state +
                ", value=" + value +
                ", x=" + x +
                ", y=" + y +
                "} " + super.toString();
    }
}

class Case extends Position implements PathExplorer<Case> {
    public boolean visited = false;
    public boolean hasHunter = false;
    public Collection<Case> neighbourg = new ArrayList<>();
    public Map<Integer, Integer> valueById = new HashMap<>();
    public boolean seenThisturn = false;

    @Override
    public String toString() {
        return "Case{" +
                "visited=" + visited +
                ", seenThisturn=" + seenThisturn +
                ", x=" + x +
                ", y=" + y +
                '}';
    }

    @Override
    public Collection<Case> getNeighbourg() {
        return neighbourg;
    }
}

class PathCalculator<T extends PathExplorer<T>> implements Runnable {


    private final Queue<T> queue;
    private final Function<T, Integer> getValue;
    private final BiConsumer<T, Integer> setValue;
    private final CountDownLatch latch;

    PathCalculator(final T starting, Function<T, Integer> getValue, BiConsumer<T, Integer> setValue, CountDownLatch latch) {
        queue = new ArrayDeque<>();
        queue.add(starting);
        this.getValue = getValue;
        this.setValue = setValue;
        setValue.accept(starting, 0);
        this.latch = latch;
    }

    @Override
    public void run() {
        while (!queue.isEmpty()) {

            T current = queue.poll();
            Integer currentValuePlusOne = getValue.apply(current) + 1;
            current.getNeighbourg().forEach((n) -> {
                calculateNeighbourg(current, currentValuePlusOne, n);
            });
        }
        latch.countDown();
    }

    private void calculateNeighbourg(T current, Integer currentValuePlusOne, T neighbourg) {
        if (neighbourg != null) {
            Integer neighbourgValue = getValue.apply(neighbourg);
            if (neighbourgValue > currentValuePlusOne) {
                setValue.accept(neighbourg, currentValuePlusOne);
                queue.add(neighbourg);
            }
        }
    }
}


abstract class Position {
    public int x;
    public int y;

    public double distance(Position other) {
        return distance(other.x, other.y);
    }

    public double distance(int otherx, int othery) {
        return Math.sqrt(
                (x - otherx) * (x - otherx) +
                        (y - othery) * (y - othery)
        );
    }

}


interface PathExplorer<T> {
    Collection<T> getNeighbourg();
}


class Pair<T, U> {
    public T first;
    public U second;

    public Pair(T first, U second) {
        this.first = first;
        this.second = second;
    }
}

interface Strategie<T> extends Consumer<T> {
    boolean runThisStrategie();
}

class StunEnemyWithGhostStrategy implements Strategie<Hunter> {

    @Override
    public boolean runThisStrategie() {
        return !Player.seenEnnemy.isEmpty() && !Player.seenEnnemy.stream().allMatch((e) -> e.state == Player.STATE_STUN);
    }

    @Override
    public void accept(final Hunter hunter) {
        Player.seenEnnemy.stream()
                .filter((e) -> e.state == Player.STATE_BUSTER_HAS_GHOST && e.distance(hunter) < Player.STUN_RANGE)
                .findFirst()
                .ifPresent(hunter::stun);
    }
}


class LOG {

    public static void debug(Collection<Ghosts> entity) {
        System.err.println(entity.size());
    }

    public static void debug(Object string) {
        System.err.println(string);
    }

    public static void debug(char c) {
        System.err.println(c);
    }

    public static void debugNoReturn(Object s) {
        System.err.print(s);
    }

    public static void debugNoReturn(char c) {
        System.err.print(c);
    }
}


