package com.credflow.controller;

import com.credflow.entity.*;
import com.credflow.platform.Platform;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
public class ApplicationAmountsController {
	private final EntityManager em;
	private final Platform p;

	public ApplicationAmountsController(EntityManager em, Platform p) {
		this.em = em;
		this.p = p;
	}

	@GetMapping("/api/v1/application-amounts")
	@Transactional(readOnly = true)
	public Object amounts(@RequestParam List<UUID> ids, @AuthenticationPrincipal Jwt jwt) {
		if (ids.isEmpty() || ids.size() > 100)
			throw new ResponseStatusException(BAD_REQUEST, "Request 1 to 100 application IDs.");
		var workflows = em.createQuery("select w from CfWorkflow w where w.id in :ids", WorkflowRow.class)
				.setParameter("ids", ids).getResultList();
		for (var w : workflows)
			p.owner(jwt, w.getSellerSubject(), w.getSellerId(), w.getLenderId(), w.getMarketplaceId(), true);
		var offers = em.createQuery("select o from CfOffer o where o.applicationId in :ids", OfferRow.class)
				.setParameter("ids", workflows.stream().map(WorkflowRow::getId).toList()).getResultList();
		var byApp = new HashMap<UUID, OfferRow>();
		for (var o : offers)
			byApp.put(o.getApplicationId(), o);
		var result = new ArrayList<Map<String, Object>>();
		for (var w : workflows) {
			var row = new LinkedHashMap<String, Object>();
			row.put("applicationId", w.getId());
			row.put("requestedAmount", w.getAmount().setScale(2).toPlainString());
			var offer = byApp.get(w.getId());
			row.put("offeredAmount", offer == null ? null : offer.getAmount().setScale(2).toPlainString());
			row.put("offerStatus", offer == null ? null : offer.getStatus());
			result.add(row);
		}
		return Map.of("items", result);
	}
}
