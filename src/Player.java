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

    public static final int VISION_RANGE = 2200; //real range

    public static final int MOVE_RANGE = 800;

    public static final int CASE_SIZE = 500;

    public static final int NB_CASE_VISION = VISION_RANGE / CASE_SIZE;

    public static final int NB_HEIGTH = MAX_HEIGTH / CASE_SIZE;
    public static final int NB_WIDTH = MAX_WIDTH / CASE_SIZE;


    public static final int RELEASE_RANGE = 1600;
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

    public static SortedSet<Hunter> myUnits = new TreeSet<>();
    public static Map<Integer, Hunter> myUnitsMap = new HashMap<>();

    public static SortedSet<Hunter> enemyList = new TreeSet<>();
    public static Map<Integer, Hunter> enemyUnit = new HashMap<>();
    public static Collection<Hunter> seenEnnemy = new ArrayList<>();


    public static Map<Integer, Ghosts> ghosts = new HashMap<>();

    public static Collection<Ghosts> seenGhost = new ArrayList<>();

    public static Case[][] caseMap = new Case[NB_WIDTH][NB_HEIGTH];
    public static Collection<Case> allCase = new ArrayList<>(NB_WIDTH * NB_HEIGTH);
    public static Case myTeamCase;
    public static Case enemyTeamCaseForStun;


    private static Strategie<Hunter> strategies[] = asArray(
            new HasStunStrategie(),
            new HasGhostStrategy(),
            new StunEnemyWithGhostStrategy(),
            new AlreadyTargetStrategie(),
            new BustStrategie(),
            new MoveToGhostSeenStrategie(),
            new MoveToPreviousSeenStrategie(),
            new MoveToUnseeMapStrategie()
    );

    private static GroupStrategie<Hunter> groupStrategies[] = asArray(
            new OneMoveToEnemyBaseForStun()
    );


    private static ExecutorService EXECUTORS;

    public static int turn = 0;

    public static void main(String args[]) {
        Scanner in = new Scanner(System.in);
        int bustersPerPlayer = in.nextInt(); // the amount of busters you control
        int ghostCount = in.nextInt(); // the amount of ghosts on the map
        int myTeamId = in.nextInt(); // if this is 0, your base is on the top left of the map, if it is one, on the bottom right

        EXECUTORS = Executors.newFixedThreadPool(bustersPerPlayer);

        initCases();
        initMyTeamCase(myTeamId);
        initGhost(ghostCount);


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
            while (i < groupStrategies.length) {
                GroupStrategie<Hunter> strategy = groupStrategies[i];
                if (strategy.runThisStrategie() && strategy.filter(myUnits)) {
                    strategy.accept(myUnits);
                }
                i++;
            }

            i = 0;
            while (i < strategies.length) {
                Strategie<Hunter> strategy = strategies[i];
                if (strategy.runThisStrategie()) {
                    myUnits.stream()
                            .filter((hunter -> hunter.action == null))
                            .filter(strategy::filter)
                            .forEach(strategy::accept);
                }
                i++;
            }

            myUnits.forEach((hunter) -> System.out.println(hunter.action));

            turn++;
        }
    }

    @SafeVarargs
    private static <T> T[] asArray(T... strategiies) {
        return strategiies;
    }


    private static void initGhost(int ghostCount) {
        for (int i = 0; i < ghostCount; i++) {
            ghosts.put(i, new Ghosts(i));
        }
    }

    private static void initMyTeamCase(int myTeamId) {
        myTeamCase = new Case();
        enemyTeamCaseForStun = new Case();
        if (myTeamId == 0) {
            myTeamCase.x = 0;
            myTeamCase.y = 0;
            enemyTeamCaseForStun.x = MAX_WIDTH - 2000;
            enemyTeamCaseForStun.y = MAX_HEIGTH - 2000;
        } else {
            myTeamCase.x = MAX_WIDTH;
            myTeamCase.y = MAX_HEIGTH;
            enemyTeamCaseForStun.x = 2000;
            enemyTeamCaseForStun.y = 2000;
        }
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
            Set<Case> inRange = PathCalculator.getInRange(u.casePos, NB_CASE_VISION, (c) -> c.valueById.get(u.entityId));
            inRange.forEach((c) -> {
                c.visited = true;
                c.seenThisturn = true;
            });
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
        //wait all path calculated
        try {
            latch.await(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            System.err.println("InterruptedException " + e);
        }
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
        e.casePos = aCase;
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

    public void move(Ghosts pos) {
        //on ne s'approche jamais a plus de 1000 d'un ghost
        int xMove = pos.x;
        int yMove = pos.y;
        if (this.x == pos.x) {
            yMove += this.y > pos.y ? 1000 : -1000;
        } else if (this.y == pos.y) {
            xMove += this.x > pos.x ? 1000 : -1000;
        } else {
            //thales = de/bd  = ae/ac = ad/ab
            double ab = this.distance(pos);
            int ad = 1000;
            int ac = pos.y - this.y;
            int bc = pos.x - this.x;
            double ae = ad / ab * ac;
            double de = ad / ab * bc;
            xMove = (int) (pos.x - de);
            yMove = (int) (pos.y - ae);

        }

        this.move(xMove, yMove);
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
            current.getNeighbourg()
                    .forEach((n) -> calculateNeighbourg(current, currentValuePlusOne, n));
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

    public static <T extends PathExplorer<T>> Set<T> getInRange(T starting, int range, Function<T, Integer> getValue) {
        Queue<T> queues = new ArrayDeque<>();
        queues.add(starting);
        Set<T> inRange = new HashSet<>();
        while (!queues.isEmpty()) {
            T c = queues.poll();
            inRange.add(c);
            c.getNeighbourg().forEach((n) -> {
                Integer nValue = getValue.apply(n);
                if (!inRange.contains(n) && nValue <= range) {
                    queues.add(n);
                }
            });
        }
        return inRange;

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
    T first;
    U second;

    public Pair(T first, U second) {
        this.first = first;
        this.second = second;
    }
}

interface GroupStrategie<T> extends Strategie<Collection<T>> {
}


interface Strategie<T> extends Consumer<T> {
    boolean runThisStrategie();

    boolean filter(T t);

}

class OneMoveToEnemyBaseForStun implements GroupStrategie<Hunter> {

    @Override
    public boolean runThisStrategie() {
        return true;
    }

    @Override
    public boolean filter(Collection<Hunter> hunters) {
        return hunters.stream().allMatch((h) -> h.casePos != Player.enemyTeamCaseForStun);
    }

    @Override
    public void accept(Collection<Hunter> hunters) {

        hunters.stream()
                .filter((h) -> h.state != Player.STATE_STUN && h.state != Player.STATE_BUSTER_HAS_GHOST && h.lastStunTurned > Player.turn + 20)
                .min(Comparator.comparingDouble(value -> value.distance(Player.enemyTeamCaseForStun)))
                .ifPresent((h) -> h.move(Player.enemyTeamCaseForStun));

    }
}

class CampForStunStrategy implements Strategie<Hunter> {

    @Override
    public boolean runThisStrategie() {
        return true;
    }

    @Override
    public boolean filter(Hunter hunter) {
        return hunter.casePos.distance(Player.enemyTeamCaseForStun) == 0;
    }

    @Override
    public void accept(Hunter hunter) {
        Optional<Hunter> enemy = Player.seenEnnemy.stream().filter((e) -> e.distance(hunter) < Player.STUN_RANGE).findFirst();
        if(enemy.isPresent()) {
            hunter.stun(enemy.get());
        } else {
            hunter.move(hunter);
        }
    }
}



class HasGhostStrategy implements Strategie<Hunter> {
    @Override
    public boolean runThisStrategie() {
        return true;
    }

    @Override
    public boolean filter(Hunter hunter) {
        return hunter.state == Player.STATE_BUSTER_HAS_GHOST;
    }


    @Override
    public void accept(Hunter hunter) {
        if (hunter.distance(Player.myTeamCase) <= Player.RELEASE_RANGE) {
            hunter.release();
            Player.ghosts.get(hunter.value).captured = true;
            LOG.debug(hunter.entityId + ": release my ghost");
        } else {
            //stun defensif
            Optional<Hunter> stunableEnemy = Player.seenEnnemy.stream()
                    .filter((e) -> e.state != Player.STATE_STUN && e.distance(hunter) < Player.STUN_RANGE)
                    .findFirst();
            if (hunter.lastStunTurned + 20 < Player.turn && stunableEnemy.isPresent()) {
                hunter.stun(stunableEnemy.get());
            } else {
                hunter.move(Player.myTeamCase);
                LOG.debug(hunter.entityId + ": back to home");
            }
        }
    }
}


class StunEnemyWithGhostStrategy implements Strategie<Hunter> {

    @Override
    public boolean runThisStrategie() {
        return !Player.seenEnnemy.isEmpty() && !Player.seenEnnemy.stream().allMatch((e) -> e.state == Player.STATE_STUN);
    }

    @Override
    public boolean filter(Hunter hunter) {
        return hunter.state == Player.STATE_BUSTER_HAS_GHOST;
    }

    @Override
    public void accept(final Hunter hunter) {
        Player.seenEnnemy.stream()
                .filter((e) -> e.distance(hunter) < Player.STUN_RANGE)
                .findFirst()
                .ifPresent(hunter::stun);
    }
}

class HasStunStrategie implements Strategie<Hunter> {

    @Override
    public boolean runThisStrategie() {
        return true;
    }

    @Override
    public boolean filter(Hunter hunter) {
        return hunter.state == Player.STATE_STUN;
    }

    @Override
    public void accept(Hunter hunter) {
        hunter.move(hunter);
        LOG.debug(hunter.entityId + ":stunned");
    }
}


class AlreadyTargetStrategie implements Strategie<Hunter> {

    @Override
    public boolean runThisStrategie() {
        return !Player.seenGhost.isEmpty();
    }

    @Override
    public boolean filter(Hunter hunter) {
        return true;
    }

    @Override
    public void accept(Hunter hunter) {
        Optional<Ghosts> ghost = Player.seenGhost.stream()
                .filter((g) -> !g.captured && g.huntedBy == hunter)
                .findFirst();
        if (ghost.isPresent()) {
            Ghosts g = ghost.get();
            double distance = g.distance(hunter);
            if (distance <= Player.BUST_MAX && distance >= Player.BUST_MIN) {
                LOG.debug(hunter.entityId + ": bust my previous unted " + g.entityId);
                hunter.bust(g);
            } else {
                LOG.debug(hunter.entityId + ": move to my previous unted " + g.entityId);
                hunter.move(g);
            }
        }
    }

}

class BustStrategie implements Strategie<Hunter> {

    @Override
    public boolean runThisStrategie() {
        return true;
    }

    @Override
    public boolean filter(Hunter hunter) {
        return true;
    }

    @Override
    public void accept(Hunter hunter) {

        Set<Ghosts> ghostsNoCapturedNoHunted = Player.seenGhost.stream()
                .filter((g) -> !g.captured && (g.huntedBy == null || g.state > 10))
                .collect(Collectors.toSet());

        Optional<Ghosts> ghost = ghostsNoCapturedNoHunted
                .stream()
                .filter((g) -> {
                    double distance = g.distance(hunter);
                    return distance <= Player.BUST_MAX && distance >= Player.BUST_MIN;
                }).min(comparingInt((g) -> g.state));
        if (ghost.isPresent()) {
            Ghosts g = ghost.get();
            LOG.debug(hunter.entityId + ": bust " + g.entityId);
            hunter.bust(g);
            g.huntedBy = hunter;
        }
    }

}

class MoveToGhostSeenStrategie implements Strategie<Hunter> {

    @Override
    public boolean runThisStrategie() {
        return !Player.seenGhost.isEmpty();
    }

    @Override
    public boolean filter(Hunter hunter) {
        return true;
    }

    @Override
    public void accept(Hunter hunter) {
        Set<Ghosts> ghostsNoCapturedNoHunted = Player.seenGhost.stream()
                .filter((g) -> !g.captured && g.huntedBy == null).collect(Collectors.toSet());
        Optional<Ghosts> ghost = ghostsNoCapturedNoHunted.stream()
                .filter((g) -> g.distance(hunter) <= Player.VISION_RANGE)
                .min(comparingInt((g) -> g.state));
        if (ghost.isPresent()) {
            Ghosts g = ghost.get();
            LOG.debug(hunter.entityId + ": move to seen " + g.entityId);
            hunter.move(g);
            g.huntedBy = hunter;
        }
    }


}


class MoveToPreviousSeenStrategie implements Strategie<Hunter> {

    @Override
    public boolean runThisStrategie() {
        return true;
    }

    @Override
    public boolean filter(Hunter hunter) {
        return true;
    }

    @Override
    public void accept(Hunter hunter) {
        // va vers un fantomes deja localise
        Optional<Ghosts> ghost = Player.ghosts.values().stream()
                .filter((g) -> !g.captured && g.huntedBy == null && g.casePos != null)
                .min(comparingInt((ToIntFunction<Ghosts>) (g) -> g.state)
                        .thenComparingDouble((g) -> g.casePos.distance(hunter)));

        if (ghost.isPresent()) {
            Ghosts g = ghost.get();
            LOG.debug(hunter.entityId + ": move to previously seen " + g.entityId);
            hunter.move(g);
            g.huntedBy = hunter;
        }
    }


}

class MoveToUnseeMapStrategie implements Strategie<Hunter> {

    @Override
    public boolean runThisStrategie() {
        return true;
    }

    @Override
    public boolean filter(Hunter hunter) {
        return true;
    }

    @Override
    public void accept(Hunter hunter) {

        Optional<Case> notvisitedCase = getNotVisited(hunter);

        Case aCase = notvisitedCase.orElseGet(() -> Player.allCase.stream().findAny().get());

        aCase.hasHunter = true;
        //FIXME meilleur truc
        aCase.neighbourg.forEach((c) -> c.hasHunter = true);
        Set<Case> inRange = PathCalculator.getInRange(hunter.casePos, Player.NB_CASE_VISION, (c) -> c.valueById.get(hunter.entityId));
        inRange.forEach((c) -> c.hasHunter = true);

        LOG.debug(hunter.entityId + ": move to useen " + aCase);
        hunter.move(aCase);
    }

    private static Optional<Case> getNotVisited(Hunter hunter) {
        return Player.allCase.stream()
                .filter((c) -> !c.visited && !c.hasHunter)
                .min(comparingInt((c) -> c.valueById.get(hunter.entityId)));
    }

}

class LOG {

    static void debug(Object string) {
        System.err.println(string);
    }

    static void debug(char c) {
        System.err.println(c);
    }

    static void debugNoReturn(Object s) {
        System.err.print(s);
    }

    static void debugNoReturn(char c) {
        System.err.print(c);
    }
}


