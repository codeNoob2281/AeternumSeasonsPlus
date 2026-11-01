package Kinkin.aeternum.calendar;

public enum Season {
    SPRING,
    SUMMER,
    AUTUMN,
    WINTER;

    private Season() {
    }

    public String display() {
        String var10000;
        switch (this.ordinal()) {
            case 0 -> var10000 = "Primavera";
            case 1 -> var10000 = "Verano";
            case 2 -> var10000 = "Otoño";
            case 3 -> var10000 = "Invierno";
            default -> throw new MatchException((String)null, (Throwable)null);
        }

        return var10000;
    }

    // $FF: synthetic method
    private static Season[] $values() {
        return new Season[]{SPRING, SUMMER, AUTUMN, WINTER};
    }
}
