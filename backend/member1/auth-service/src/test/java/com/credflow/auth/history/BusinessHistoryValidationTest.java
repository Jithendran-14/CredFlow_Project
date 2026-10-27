package com.credflow.auth.history;
import java.time.*;import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class BusinessHistoryValidationTest {
 final YearMonth end=YearMonth.of(2026,10);final LocalDate start=LocalDate.of(2020,1,1);
 BusinessHistoryInput.Month month(String m,String gross,String returns,long orders,long returned){return new BusinessHistoryInput.Month(m,"AVAILABLE",gross,returns,"90000.00",orders,returned);}
 List<BusinessHistoryInput.Month> valid(){return new ArrayList<>(List.of(month("2026-07","100000.00","1000.00",100,1),month("2026-08","100000.00","1000.00",100,1),month("2026-09","100000.00","1000.00",100,1)));}
 @Test void acceptsExactCompletedMonthlyRecords(){assertDoesNotThrow(()->BusinessHistoryService.validate(new BusinessHistoryInput(0,true,valid()),start,end));}
 @Test void rejectsHistoryBeforeBusinessStarted(){assertThrows(ResponseStatusException.class,()->BusinessHistoryService.validate(new BusinessHistoryInput(0,true,valid()),LocalDate.of(2026,10,1),end));}
 @Test void rejectsDuplicateAndWrongMonths(){var rows=valid();rows.set(0,rows.get(1));assertThrows(ResponseStatusException.class,()->BusinessHistoryService.validate(new BusinessHistoryInput(0,true,rows),start,end));}
 @Test void rejectsReturnsAboveSales(){var rows=valid();rows.set(0,month("2026-07","100.00","101.00",10,1));assertThrows(ResponseStatusException.class,()->BusinessHistoryService.validate(new BusinessHistoryInput(0,true,rows),start,end));}
 @Test void rejectsReturnedOrdersAboveOrders(){var rows=valid();rows.set(0,month("2026-07","100.00","1.00",10,11));assertThrows(ResponseStatusException.class,()->BusinessHistoryService.validate(new BusinessHistoryInput(0,true,rows),start,end));}
 @Test void missingMonthIsNotZeroSales(){var rows=valid();rows.set(0,new BusinessHistoryInput.Month("2026-07","MISSING",null,null,null,null,null));assertDoesNotThrow(()->BusinessHistoryService.validate(new BusinessHistoryInput(0,false,rows),start,end));rows.set(0,new BusinessHistoryInput.Month("2026-07","MISSING","0.00",null,null,null,null));assertThrows(ResponseStatusException.class,()->BusinessHistoryService.validate(new BusinessHistoryInput(0,false,rows),start,end));}
 @Test void rejectsFractionsBeyondTwoDecimals(){var rows=valid();rows.set(0,month("2026-07","100.001","1.00",10,1));assertThrows(ResponseStatusException.class,()->BusinessHistoryService.validate(new BusinessHistoryInput(0,true,rows),start,end));}
}
