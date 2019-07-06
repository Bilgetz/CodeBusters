import java.util.*;

/**
 * Send your busters out into the fog to trap ghosts and bring them home!
 **/
class Player {

    public static int MAX_WIDTH = 16000;
    public static int MAX_HEIGTH = 9000;

    //    private static final int VISION_RANGE = 2200 ; //real range
    public static final int VISION_RANGE = 2000;

    public static final int MOVE_RANGE = 800;

    public static final int CASE_SIZE = 500;

    public static final int NB_HEIGTH = MAX_HEIGTH / CASE_SIZE;
    public static final int NB_WIDTH = MAX_WIDTH / CASE_SIZE;


    private static final int RELEASE_RANGE = 1600;
    public static int BUST_MAX = 1760;
    public static int BUST_MIN = 900;

    public static int TEAM_0_X = 0;
    public static int TEAM_0_Y = 0;

    public static int TEAM_1_X = 16000;
    public static int TEAM_1_Y = 9000;

    public static int STATE_BUSTER_NO_GHOST = 0; // Buster ne transportant pas de fantôme.
    public static int STATE_BUSTER_HAS_GHOST = 1; // Buster transportant un fantôme.

    public static SortedSet<Hunter> myUnits;
    public static Map<Integer, Hunter> myUnitsMap;

    public static SortedSet<Hunter> enemyList;
    public static Map<Integer, Hunter> enemyUnit;

    public static Map<Integer, Ghosts> ghosts;

    public static Collection<Ghosts> seenGhost = new ArrayList<>();

    public static Case[][] caseMap = new Case[NB_WIDTH][NB_HEIGTH];
    public static Collection<Case> allCase = new ArrayList<>(NB_WIDTH * NB_HEIGTH);
    public static Case myTeamCase;

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

        initCases();

        myTeamCase = new Case();
        if (myTeamId == 0) {
            myTeamCase.x = 0;
            myTeamCase.y = 0;
        } else {
            myTeamCase.x = MAX_WIDTH;
            myTeamCase.y = MAX_HEIGTH;
        }


        for (int i = 0; i < ghostCount; i++) {
            ghosts.put(i, new Ghosts(i));
        }

        // game loop
        while (true) {

            resetCase();
            seenGhost.clear();


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

            majPath();


            myUnits.forEach((hunter) -> {

                if (hunter.state == STATE_BUSTER_HAS_GHOST) {
                    if (hunter.distance(myTeamCase) <= RELEASE_RANGE) {
                        hunter.release();
                        ghosts.get(hunter.value).captured = true;
                    } else {
                        hunter.move(myTeamCase);
                    }
                    return;
                }

                if (hunter.state == STATE_BUSTER_NO_GHOST) {
                    Optional<Ghosts> ghost = seenGhost.stream()
                            .filter((g) -> !g.captured && g.huntedBy == null)
                            .filter((g) -> {
                                double distance = g.distance(hunter);
                                return distance <= BUST_MAX && distance >= BUST_MIN;
                            }).findFirst();
                    if (ghost.isPresent()) {
                        Ghosts g = ghost.get();
                        hunter.bust(g);
                        g.huntedBy = hunter;
                        return;
                    }
                }


                Optional<Case> notvisitedCase = getNotVisited(hunter);

                Case aCase = notvisitedCase.orElseGet(() -> allCase.stream().findAny().get());

                aCase.hasHunter = true;
                aCase.neighbourg.forEach((c) -> c.hasHunter = true);
                hunter.move(aCase);


            });
        }
    }

    private static void majPath() {
        myUnits.forEach((u) -> {
            Queue<Case> queues = new ArrayDeque<>();
            queues.add(u.casePos);
            int entityId = u.entityId;
            u.casePos.valueById.put(entityId, 0);

            while (!queues.isEmpty()) {
                Case c = queues.poll();
                Integer cValue = c.valueById.get(entityId);
                c.neighbourg.forEach((n) -> {
                    Integer value = n.valueById.getOrDefault(entityId, Integer.MAX_VALUE);
                    if (value > cValue + 1) {
                        n.valueById.put(entityId, cValue + 1);
                        queues.add(n);
                    }
                });
            }
        });
    }

    private static Optional<Case> getNotVisited(Hunter hunter) {
        return allCase.stream()
                .filter((c) -> !c.visited && !c.hasHunter)
                .sorted(Comparator.comparingInt((c) -> c.valueById.get(hunter.entityId)))
                .findFirst();
    }

    private static void majEntity(int myTeamId, int entityId, int x, int y, int entityType, int state, int value) {
        Entity e;

        Case aCase = caseMap[x != MAX_WIDTH ? x / Player.CASE_SIZE : NB_WIDTH - 1][y != MAX_HEIGTH ? y / Player.CASE_SIZE : NB_HEIGTH - 1];
        if (entityType == -1) {
            e = ghosts.get(entityId);
            seenGhost.add((Ghosts) e);
            ((Ghosts) e).huntedBy = null;
        } else if (myTeamId == entityType) {
            e = myUnitsMap.get(entityId);
            if (e == null) {
                e = new Hunter(entityId);
                myUnits.add((Hunter) e);
                myUnitsMap.put(entityId, (Hunter) e);
            }
            aCase.visited = true;
            aCase.neighbourg.forEach((c) -> c.visited = true);
        } else {
            e = enemyUnit.get(entityId);
            if (e == null) {
                e = new Hunter(entityId);
                enemyList.add((Hunter) e);
                enemyUnit.put(entityId, (Hunter) e);
            }
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
    public Hunter(int id) {
        this.entityId = id;
    }

    public void bust(Ghosts g) {
        this.bust(g.entityId);
    }

    public void bust(int idGhost) {
        System.out.println("BUST " + idGhost);
    }

    public void release() {
        System.out.println("RELEASE");
    }


}


abstract class Entity extends Position implements Comparable<Entity> {
    public int entityId; // buster id or ghost id
    public int entityType; // the team id if it is a buster, -1 if it is a ghost.
    public int entityRole; // -1 for ghosts, 0 for the HUNTER, 1 for the GHOST CATCHER and 2 for the SUPPORT
    public int state; // For busters: 0=idle, 1=carrying a ghost. For ghosts: remaining stamina points.
    public int value; // For busters: Ghost id being carried/busted or number of turns left when stunned. For ghosts: number of busters attempting to trap this ghost.
    public Case casePos;

    private String getMessage() {
        if (entityRole == 1) {
            return "DevOups! > Chtazii";
        }
        return "";
    }

    public void move(Position pos) {
        move(pos.x, pos.y);
    }

    public void move(int x, int y) {
        System.out.println("MOVE " + x + " " + y + " " + getMessage());
    }


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

class Case extends Position {
    public boolean visited = false;
    public boolean hasHunter = false;
    public Collection<Case> neighbourg = new ArrayList<>();
    public Map<Integer, Integer> valueById = new HashMap<>();
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