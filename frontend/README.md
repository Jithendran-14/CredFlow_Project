# CredFlow role portals

HTML, CSS, JavaScript and assets only. All records come from the backend; no demo dataset is bundled.

Use the project root `start.cmd` to launch Kafka, services and all four portals: Marketplace 5174, Seller 5175, all Lenders 5176, Admin 5178. Sellers can register from port 5175. Use the correct role account for each portal.

`node --test tests/contracts.test.mjs` runs adapter and signed-token access-control tests. `npm start` starts only the marketplace web process; it does not start the full project. The backend server and trusted portal identities live under `backend/web`.

Registered sellers use Apply for loan on port 5175, review the saved business profile, enter amount/purpose and authorise initial sharing. My loan requests tracks backend submission and links to separate CredFlow permissions after application creation. Marketplace staff can track requests on port 5174. No marketplace credential or synthetic business data is bundled in the browser.

Lender portal > Lender rules edits its own versioned eligibility/routing rules and displays history. Sellers use Correct application only on an eligible ACTION_REQUIRED application before dispatch; the Corrections tab tracks processing. Review financial permission for the new revision after saving. Marketplace CRUD and editable contact profiles are documented in the root README.

Application lists show Amount requested and Amount offered separately. Application timeline is shown only in the Timeline tab. Terms PDF attachment is optional; complete offer terms and expiry remain required. Use one top Refresh button to reload the current view without signing out.

Forgot password is available inside Sign in on every portal. Reset emails are captured locally at http://localhost:8025. Open the newest link, enter matching 12-72 character passwords and sign in. Expired or used links require a new request. See the root README for limits and local-only inbox behavior.

Phase 0 loan requests include preferred tenure, declared income/expenses/debt and prototype KYC. A seller may have one active request per purpose. Marketplace staff can answer lender information requests for their own sellers. Bank C and every other registered lender use the shared officer portal on 5176. Statuses include Draft, Submitted, Action Required, Under Review, Approved and Not Eligible. Offer PDF upload is optional.

Admin > Lenders / banks supports lender registration and linked officer credentials. New lenders have an inactive product until the officer reviews and activates its rules. Routing priorities remain admin-only, with a reason required for atomic ordered moves.
