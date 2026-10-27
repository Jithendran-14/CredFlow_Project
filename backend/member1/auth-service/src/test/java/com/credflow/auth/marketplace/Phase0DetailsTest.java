package com.credflow.auth.marketplace;
import org.junit.jupiter.api.Test;import java.time.*;import static org.junit.jupiter.api.Assertions.*;
class Phase0DetailsTest {
 Phase0Details data(LocalDate birth,int tenure,String income,String expenses,boolean terms){return new Phase0Details(tenure,income,expenses,"1000.00",birth,"MOCK_ID","TEST-IDENTITY001",true,terms);}
 @Test void adultAndFinancesAreRequired(){assertDoesNotThrow(()->data(LocalDate.now(ZoneOffset.UTC).minusYears(18),12,"50000.00","10000.00",true).validate());assertThrows(IllegalArgumentException.class,()->data(LocalDate.now(ZoneOffset.UTC).minusYears(18).plusDays(1),12,"50000.00","10000.00",true).validate());assertThrows(IllegalArgumentException.class,()->data(LocalDate.of(1990,1,1),0,"50000.00","10000.00",true).validate());assertThrows(IllegalArgumentException.class,()->data(LocalDate.of(1990,1,1),12,"0.00","10000.00",true).validate());assertThrows(IllegalArgumentException.class,()->data(LocalDate.of(1990,1,1),12,"50000.00","-1.00",true).validate());assertThrows(IllegalArgumentException.class,()->data(LocalDate.of(1990,1,1),12,"50000.00","10000.00",false).validate());}
}
