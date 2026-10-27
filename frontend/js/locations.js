export const states=Object.freeze([
 'Andaman and Nicobar Islands','Andhra Pradesh','Arunachal Pradesh','Assam','Bihar','Chandigarh','Chhattisgarh','Dadra and Nagar Haveli and Daman and Diu','Delhi','Goa','Gujarat','Haryana','Himachal Pradesh','Jammu and Kashmir','Jharkhand','Karnataka','Kerala','Ladakh','Lakshadweep','Madhya Pradesh','Maharashtra','Manipur','Meghalaya','Mizoram','Nagaland','Odisha','Puducherry','Punjab','Rajasthan','Sikkim','Tamil Nadu','Telangana','Tripura','Uttar Pradesh','Uttarakhand','West Bengal'
]);
const cities={
 'Andhra Pradesh':['Visakhapatnam','Vijayawada','Guntur','Tirupati'],
 'Arunachal Pradesh':['Itanagar','Naharlagun','Tawang'],Assam:['Guwahati','Dibrugarh','Silchar'],Bihar:['Patna','Gaya','Bhagalpur'],Chandigarh:['Chandigarh'],Chhattisgarh:['Raipur','Bhilai','Bilaspur'],
 'Dadra and Nagar Haveli and Daman and Diu':['Silvassa','Daman','Diu'],Delhi:['New Delhi','Delhi'],Goa:['Panaji','Margao','Vasco da Gama'],Gujarat:['Ahmedabad','Surat','Vadodara','Rajkot'],Haryana:['Gurugram','Faridabad','Panipat'],
 'Himachal Pradesh':['Shimla','Dharamshala','Solan'],'Jammu and Kashmir':['Srinagar','Jammu'],Jharkhand:['Ranchi','Jamshedpur','Dhanbad'],Karnataka:['Bengaluru','Mysuru','Mangaluru','Hubballi'],Kerala:['Kochi','Thiruvananthapuram','Kozhikode','Thrissur'],Ladakh:['Leh','Kargil'],Lakshadweep:['Kavaratti'],
 'Madhya Pradesh':['Bhopal','Indore','Jabalpur','Gwalior'],Maharashtra:['Mumbai','Pune','Nagpur','Nashik','Thane'],Manipur:['Imphal'],Meghalaya:['Shillong','Tura'],Mizoram:['Aizawl'],Nagaland:['Kohima','Dimapur'],Odisha:['Bhubaneswar','Cuttack','Rourkela'],Puducherry:['Puducherry','Karaikal','Mahe','Yanam'],Punjab:['Ludhiana','Amritsar','Jalandhar','Patiala'],Rajasthan:['Jaipur','Jodhpur','Udaipur','Kota'],Sikkim:['Gangtok'],
 'Tamil Nadu':['Chennai','Coimbatore','Madurai','Tiruchirappalli'],Telangana:['Hyderabad','Warangal','Nizamabad'],Tripura:['Agartala'],'Uttar Pradesh':['Lucknow','Kanpur','Noida','Agra','Varanasi'],Uttarakhand:['Dehradun','Haridwar','Haldwani'],'West Bengal':['Kolkata','Howrah','Siliguri']
};
export const citySuggestions=state=>cities[state]||[];
