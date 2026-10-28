package com.credflow.simulator.financial;

import java.util.*;
import java.time.*;
import com.fasterxml.jackson.databind.*;
import com.credflow.simulator.history.MarketplaceHistorySource;
import org.springframework.transaction.support.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class MarketplaceFinancialHistoryTest {
	@Test
	void providerUsesSavedMonthlyRecordsAndPinsReceipts() throws Exception {
		var json = new ObjectMapper();
		var source = mock(MarketplaceHistorySource.class);
		var records = mock(FinancialMockRecords.class);
		var tx = mock(TransactionTemplate.class);
		var store = new HashMap<String, FinancialMockRecord>();
		when(records.findById(anyString())).thenAnswer(a -> Optional.ofNullable(store.get(a.getArgument(0))));
		when(records.saveAndFlush(any())).thenAnswer(a -> {
			FinancialMockRecord r = a.getArgument(0);
			store.put(r.key, r);
			return r;
		});
		when(tx.execute(any()))
				.thenAnswer(a -> ((org.springframework.transaction.support.TransactionCallback<?>) a.getArgument(0))
						.doInTransaction(new SimpleTransactionStatus()));
		var end = YearMonth.now(ZoneOffset.UTC);
		String first = end.minusMonths(3).toString();
		var payload = json.createObjectNode();
		var row = payload.putArray("months").addObject();
		row.put("month", first);
		row.put("status", "AVAILABLE");
		row.put("grossSalesAmount", "712345.67");
		row.put("returnsAmount", "1234.00");
		row.put("payoutAmount", "700000.00");
		row.put("orderCount", 77);
		row.put("returnedOrderCount", 2);
		when(source.financial("MPH-test")).thenReturn(Optional.of(payload));
		var service = new FinancialMockService(records, json, tx, source);
		var id = UUID.randomUUID();
		var request = new FinancialMockService.Request(id, "MPH-test", FinancialMockService.SCOPE,
				LocalDate.now(ZoneOffset.UTC), end.minusMonths(3).atDay(1), end.atDay(1));
		var result = service.retrieve(request, id.toString());
		assertEquals("712345.67", result.path("dataset").path("months").get(0).path("grossSalesAmount").asText());
		assertEquals("MISSING", result.path("dataset").path("months").get(1).path("status").asText());
		assertFalse(result.path("dataset").path("months").get(1).has("grossSalesAmount"));
		row.put("grossSalesAmount", "1.00");
		assertEquals(result, service.retrieve(request, id.toString()));
	}
}
