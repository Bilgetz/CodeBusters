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

    public static SortedSet<Hunter> myList;
    public static Map<Integer, Hunter> myMap;

    public static SortedSet<Hunter> enemyList;
    public static Map<Integer, Hunter> enemyMap;

    public static Map<Integer, Ghosts> ghosts;

    public static Case[][] caseMap = new Case[NB_WIDTH][NB_HEIGTH];

    public static void main(String args[]) {
        Scanner in = new Scanner(System.in);
        int bustersPerPlayer = in.nextInt(); // the amount of busters you control
        int ghostCount = in.nextInt(); // the amount of ghosts on the map
        int myTeamId = in.nextInt(); // if this is 0, your base is on the top left of the map, if it is one, on the bottom right

        myList = new TreeSet<>();
        enemyList = new TreeSet<>();

        myMap = new HashMap<>();
        enemyMap = new HashMap<>();

        ghosts = new HashMap<>();

        for (int j = 0; j < NB_HEIGTH; j++) {
            for (int i = 0; i < NB_WIDTH; i++) {
                caseMap[i][j] = new Case();
            }
        }

        for (int j = 0; j < NB_HEIGTH; j++) {
            for (int i = 0; i < NB_WIDTH; i++) {
                Case currentCase = caseMap[i][j];
                if (j > 0) {
                    currentCase.neighbourg.add(caseMap[i][j - 1]);
                }
                if ((j + 1) < NB_HEIGTH) {
                    currentCase.neighbourg.add(caseMap[i][j + 1]);
                }
                if (i > 0) {
                    currentCase.neighbourg.add(caseMap[i - 1][j]);
                }
                if ((i + 1) < NB_WIDTH) {
                    currentCase.neighbourg.add(caseMap[i + 1][j]);
                }
            }
        }


        for (int i = 0; i < ghostCount; i++) {
            ghosts.put(i, new Ghosts(i));
        }

        // game loop
        while (true) {
            int entities = in.nextInt(); // the number of busters and ghosts visible to you
            for (int i = 0; i < entities; i++) {
                int entityId = in.nextInt(); // buster id or ghost id
                int x = in.nextInt();
                int y = in.nextInt(); // position of this buster / ghost
                int entityType = in.nextInt(); // the team id if it is a buster, -1 if it is a ghost.
                int state = in.nextInt(); // For busters: 0=idle, 1=carrying a ghost.
                int value = in.nextInt(); // For busters: Ghost id being carried. For ghosts: number of busters attempting to trap this ghost.

                Entity e;
                //LOG.debug(entityType);
                if (entityType == -1) {
                    e = ghosts.get(entityId);
                } else if (myTeamId == entityType) {
                    e = myMap.get(entityId);
                    if (e == null) {
                        e = new Hunter(i);
                        myList.add((Hunter) e);
                        myMap.put(i, (Hunter) e);
                    }
                    caseMap[x / Player.CASE_SIZE][y / Player.CASE_SIZE].visited = true;
                } else {
                    e = enemyMap.get(entityId);
                    if (e == null) {
                        e = new Hunter(i);
                        enemyList.add((Hunter) e);
                        enemyMap.put(i, (Hunter) e);
                    }
                }
                //LOG.debug(e);
                e.x = x;
                e.y = y;
                e.state = state;
                e.value = value;
            }
//            for (int j = 0; j < NB_HEIGTH; j++) {
//                for (int i = 0; i < NB_WIDTH; i++) {
//                    LOG.debugNoReturn(Player.caseMap[i][j].visited ? "X" : "O");
//                }
//                LOG.debugNoReturn('\n');
//            }


            for (int i = 0; i < bustersPerPlayer; i++) {

                // Write an action using System.out.println()
                // To debug: System.err.println("Debug messages...");

                Hunter hunter = myMap.get(i);
                int x = -1;
                int y = 0;
                do {
                    x++;
                    y = 0;
                    while (y < NB_HEIGTH && (caseMap[x][y].visited || caseMap[x][y].hasHunter)) {
                        y++;
                    }
                    y = y == NB_HEIGTH ? NB_HEIGTH - 1 : y;
                } while (x < NB_WIDTH && (caseMap[x][y].visited || caseMap[x][y].hasHunter));

                hunter.move(x * CASE_SIZE, y * CASE_SIZE);
                caseMap[x][y].hasHunter = true;
            }
        }
    }
}


class Ghosts extends Entity {

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