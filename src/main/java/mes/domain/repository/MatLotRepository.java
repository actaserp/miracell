package mes.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import mes.domain.entity.MaterialLot;

import java.util.Optional;

@Repository
public interface MatLotRepository extends JpaRepository<MaterialLot, Integer>{

	MaterialLot getMatLotById(Integer id);

	MaterialLot findBySourceTableNameAndSourceDataPkAndLotNumber(String string, int id, String lotNumber);

	MaterialLot findBySourceDataPk(int id);

	MaterialLot getByLotNumber(String lotNumber);

	boolean existsByStoreHouseId(Integer storeHouseId);

	Optional<Object> findByMaterialIdAndLotNumberAndSpjangcd(Integer matPk, String lotNumber, String spjangcd);
}
