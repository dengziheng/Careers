# --- !Ups

ALTER TABLE PUBLIC."Matches" DROP CONSTRAINT PUBLIC.FK_MATCHES_FROMID;
ALTER TABLE PUBLIC."Searches" DROP CONSTRAINT PUBLIC.FK_SEARCHES_MATCHVECTORID;
ALTER TABLE PUBLIC."Position" DROP CONSTRAINT PUBLIC.FK_POSITION_MATCHVECTORID;
ALTER TABLE PUBLIC."CVs" DROP CONSTRAINT PUBLIC.FK_CVS_USERID;
ALTER TABLE PUBLIC."Searches" DROP CONSTRAINT PUBLIC.FK_SEARCHES_USERID;
ALTER TABLE PUBLIC."Position" DROP CONSTRAINT PUBLIC.FK_POSITION_USERID;
ALTER TABLE PUBLIC."Position" DROP CONSTRAINT PUBLIC.FK_POSITION_COMPANYID;
ALTER TABLE PUBLIC."MatchVector" DROP CONSTRAINT PUBLIC.FK_MATCHVECTOR_CVID;
ALTER TABLE PUBLIC."Matches" DROP CONSTRAINT PUBLIC.FK_MATCHES_TOID;

ALTER TABLE PUBLIC."Matches" ADD CONSTRAINT PUBLIC.FK_MATCHES_FROMID FOREIGN KEY("fromMatchId") REFERENCES PUBLIC."MatchVector"("id") ON DELETE CASCADE NOCHECK; 
ALTER TABLE PUBLIC."Searches" ADD CONSTRAINT PUBLIC.FK_SEARCHES_MATCHVECTORID FOREIGN KEY("matchVectorId") REFERENCES PUBLIC."MatchVector"("id") ON DELETE CASCADE NOCHECK;      
ALTER TABLE PUBLIC."Position" ADD CONSTRAINT PUBLIC.FK_POSITION_MATCHVECTORID FOREIGN KEY("matchVectorId") REFERENCES PUBLIC."MatchVector"("id") ON DELETE CASCADE NOCHECK;      
ALTER TABLE PUBLIC."CVs" ADD CONSTRAINT PUBLIC.FK_CVS_USERID FOREIGN KEY("userId") REFERENCES PUBLIC."Users"("id") ON DELETE CASCADE NOCHECK;    
ALTER TABLE PUBLIC."Searches" ADD CONSTRAINT PUBLIC.FK_SEARCHES_USERID FOREIGN KEY("userId") REFERENCES PUBLIC."Users"("id") ON DELETE CASCADE NOCHECK;          
ALTER TABLE PUBLIC."Position" ADD CONSTRAINT PUBLIC.FK_POSITION_USERID FOREIGN KEY("userId") REFERENCES PUBLIC."Users"("id") ON DELETE CASCADE NOCHECK;          
ALTER TABLE PUBLIC."Position" ADD CONSTRAINT PUBLIC.FK_POSITION_COMPANYID FOREIGN KEY("companyId") REFERENCES PUBLIC."Companies"("id") ON DELETE CASCADE NOCHECK;
ALTER TABLE PUBLIC."MatchVector" ADD CONSTRAINT PUBLIC.FK_MATCHVECTOR_CVID FOREIGN KEY("cvId") REFERENCES PUBLIC."CVs"("id") ON DELETE CASCADE NOCHECK;          
ALTER TABLE PUBLIC."Matches" ADD CONSTRAINT PUBLIC.FK_MATCHES_TOID FOREIGN KEY("toMatchId") REFERENCES PUBLIC."MatchVector"("id") ON DELETE CASCADE NOCHECK; 

# --- !Downs
